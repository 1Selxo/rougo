package com.selxo.rougo.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.TrackDescription
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File
import java.util.UUID

private enum class RepeatPracticePhase { Idle, RecordingAttempt, PlayingAttempt }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    initialLibraryItem: LibraryItem,
    desktopCommandEvent: DesktopPlayerCommandEvent? = null,
    onBack: (LibraryItem) -> Unit
) {
    configureVlcNativePath()
    var libraryItem by remember { mutableStateOf(initialLibraryItem) }
    val libraryManager = remember { LibraryManager() }
    val dictionaryEngine = remember { DictionaryEngine.instance }
    val prefs = Prefs.app
    val scope = rememberCoroutineScope()
    val skipSeconds = remember { prefs.getInt(PREF_SKIP_SECONDS, DEFAULT_SKIP_SECONDS).coerceIn(1, 30) }
    val skipDurationMs = skipSeconds * 1000L
    val vlcComponent = remember { EmbeddedMediaPlayerComponent() }
    val vlcPlayer = remember { vlcComponent.mediaPlayer() }
    val voiceFactory = remember { MediaPlayerFactory() }
    val voicePlayer = remember { voiceFactory.mediaPlayers().newMediaPlayer() }

    var showDictQuery by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isOpeningMedia by remember(libraryItem.id) { mutableStateOf(false) }
    var hasReachedEnd by remember { mutableStateOf(false) }
    var currentPos by remember { mutableLongStateOf(libraryItem.progress) }
    var duration by remember { mutableLongStateOf(libraryItem.duration) }
    var isSubtitlesVisible by remember { mutableStateOf(libraryItem.subtitleUri != null || libraryItem.isVideo) }
    var subtitleDelayMs by remember { mutableLongStateOf(prefs.getLong(PREF_SUBTITLE_OFFSET_MS, DEFAULT_SUBTITLE_OFFSET_MS).coerceIn(-5000L, 5000L)) }
    var parsedCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var currentSubtitleText by remember { mutableStateOf("") }
    var isParsingSubtitles by remember { mutableStateOf(libraryItem.subtitleUri != null) }
    var youtubeSubtitleChoices by remember(libraryItem.id) { mutableStateOf<List<YoutubeSubtitleChoice>>(emptyList()) }
    var isLoadingYoutubeSubtitleChoices by remember(libraryItem.id) { mutableStateOf(false) }
    var youtubeSubtitleChoicesLoaded by remember(libraryItem.id) { mutableStateOf(false) }
    var autoSubtitleRetryAttempted by remember(libraryItem.id) { mutableStateOf(false) }
    var selectedYoutubeSubtitleKey by remember(libraryItem.id) { mutableStateOf<String?>(null) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var embeddedSubtitlesEnabled by remember(libraryItem.id) { mutableStateOf(libraryItem.subtitleUri != null) }
    var selectedEmbeddedSubtitleTrackId by remember(libraryItem.id) { mutableStateOf(-1) }
    var embeddedSubtitleTracks by remember(libraryItem.id) { mutableStateOf<List<TrackDescription>>(emptyList()) }
    var actualMediaUri by remember(libraryItem.id) {
        mutableStateOf(initialPlayableMediaUri(libraryItem) ?: cachedPlayableMediaUri(libraryItem))
    }
    var isRefreshingStream by remember(libraryItem.id) { mutableStateOf(libraryItem.sourceUrl != null && actualMediaUri == null) }
    var streamRefreshAttempts by remember(libraryItem.id) { mutableStateOf(0) }
    var playWhenStreamReady by remember(libraryItem.id) { mutableStateOf(false) }
    var playbackStatusMessage by remember(libraryItem.id) { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<ShadowAudioRecorder?>(null) }
    var tempTargetFile by remember { mutableStateOf<File?>(null) }
    var recordStartTime by remember { mutableLongStateOf(0L) }
    val recordings = remember { mutableStateListOf<ShadowRecording>().apply { addAll(libraryItem.recordings) } }
    var showBacklog by remember { mutableStateOf(false) }
    var repeatPracticeSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var repeatAttemptCount by remember { mutableStateOf(0) }
    var repeatPracticePhase by remember { mutableStateOf(RepeatPracticePhase.Idle) }
    var saveRepeatRecordings by remember { mutableStateOf(prefs.getBoolean(PREF_SAVE_REPEAT_RECORDINGS, true)) }
    var activeOriginalSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var activeVoiceSegmentId by remember { mutableStateOf<String?>(null) }
    var voiceCurrentPos by remember { mutableLongStateOf(-1L) }
    val albumArt = loadAlbumArt(libraryItem.mediaUri, libraryItem.coverArtPath, libraryItem.sourceUrl)

    fun preferredYoutubeResolution(): String =
        prefs.getString(PREF_YOUTUBE_RESOLUTION, DEFAULT_YOUTUBE_RESOLUTION) ?: DEFAULT_YOUTUBE_RESOLUTION

    fun itemWithResolvedStreamHeaders(stream: ResolvedMediaStream): LibraryItem =
        libraryItem.copy(
            httpUserAgent = stream.httpUserAgent?.takeIf { it.isNotBlank() } ?: libraryItem.httpUserAgent,
            httpReferer = stream.httpReferer?.takeIf { it.isNotBlank() } ?: libraryItem.httpReferer
        )

    fun applyResolvedMediaStream(stream: ResolvedMediaStream): LibraryItem {
        actualMediaUri = stream.url
        val updatedItem = itemWithResolvedStreamHeaders(stream)
        if (updatedItem != libraryItem) {
            libraryItem = updatedItem
            libraryManager.saveItem(updatedItem)
        }
        return updatedItem
    }

    fun syncWithStorage() {
        val decision = decidePlaybackStorageItem(
            item = libraryItem,
            progress = currentPos,
            duration = duration.takeIf { it > 0L } ?: libraryItem.duration,
            recordings = recordings.toList(),
            actualMediaUri = actualMediaUri
        )
        libraryItem = decision.item
        libraryManager.saveItem(libraryItem)
    }

    fun seekMainPlayer(positionMs: Long, resumeAfterSeek: Boolean = isPlaying) {
        val upperBound = if (duration > 0L) (duration - 500L).coerceAtLeast(0L) else Long.MAX_VALUE
        val nextPosition = positionMs.coerceIn(0L, upperBound)
        runCatching {
            if (hasReachedEnd) {
                vlcPlayer.controls().stop()
                actualMediaUri?.let { vlcPlayer.media().play(it, *vlcMediaOptions(libraryItem)) }
                hasReachedEnd = false
            }
            vlcPlayer.controls().setTime(nextPosition)
            if (resumeAfterSeek) vlcPlayer.controls().play() else vlcPlayer.controls().pause()
        }
        currentPos = nextPosition
    }

    fun playMainPlayer() {
        val media = actualMediaUri ?: run {
            if (libraryItem.sourceUrl != null) {
                playWhenStreamReady = true
                isRefreshingStream = true
                isOpeningMedia = true
                playbackStatusMessage = "Resolving live stream URL..."
            }
            return
        }
        runCatching {
            isOpeningMedia = true
            playWhenStreamReady = false
            if (hasReachedEnd || (duration > 0L && currentPos >= duration - 500L)) {
                vlcPlayer.controls().stop()
                vlcPlayer.media().play(media, *vlcMediaOptions(libraryItem))
                vlcPlayer.controls().setTime(0L)
                currentPos = 0L
                hasReachedEnd = false
            } else if (!vlcPlayer.status().isPlayable) {
                vlcPlayer.media().play(media, *vlcMediaOptions(libraryItem))
                vlcPlayer.controls().setTime(currentPos)
            } else {
                vlcPlayer.controls().play()
            }
            isPlaying = true
            playbackStatusMessage = null
        }.onFailure {
            CrashReporter.recordHandled("PlayerScreen.playMainPlayer", it)
            isOpeningMedia = false
            playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
        }
    }

    fun refreshStreamAfterPlaybackError() {
        val sourceUrl = libraryItem.sourceUrl
        if (sourceUrl == null || isRefreshingStream || streamRefreshAttempts >= 1) {
            playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
            isPlaying = false
            return
        }
        scope.launch {
            streamRefreshAttempts += 1
            isRefreshingStream = true
            isOpeningMedia = false
            playbackStatusMessage = "Playback failed. Refreshing stream..."
            val refreshedStream = withContext(Dispatchers.IO) {
                invalidateResolvedStreamUrl(sourceUrl, libraryItem.formatId)
                resolveYoutubeMediaStream(sourceUrl, libraryItem.formatId, preferredYoutubeResolution())
            }
            if (refreshedStream != null) {
                val playbackItem = applyResolvedMediaStream(refreshedStream)
                playbackStatusMessage = null
                runCatching {
                    isOpeningMedia = true
                    vlcPlayer.media().play(refreshedStream.url, *vlcMediaOptions(playbackItem))
                    if (currentPos > 0L) vlcPlayer.controls().setTime(currentPos)
                }.onFailure {
                    CrashReporter.recordHandled("PlayerScreen.refreshStreamAfterPlaybackError", it)
                    isOpeningMedia = false
                    playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
                }
            } else {
                playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
                isOpeningMedia = false
                isPlaying = false
            }
            isRefreshingStream = false
        }
    }

    fun pauseMainPlayer() {
        runCatching { vlcPlayer.controls().pause() }
        playWhenStreamReady = false
        isOpeningMedia = false
        isPlaying = false
    }

    fun stopMainPlayer() {
        runCatching {
            vlcPlayer.controls().pause()
            vlcPlayer.controls().setTime(0L)
        }
        currentPos = 0L
        isOpeningMedia = false
        isPlaying = false
    }

    LaunchedEffect(desktopCommandEvent?.id) {
        when (desktopCommandEvent?.command) {
            DesktopPlayerCommand.TogglePlayPause -> if (isPlaying) pauseMainPlayer() else playMainPlayer()
            DesktopPlayerCommand.SeekBackward -> seekMainPlayer(currentPos - skipDurationMs, resumeAfterSeek = isPlaying)
            DesktopPlayerCommand.SeekForward -> seekMainPlayer(currentPos + skipDurationMs, resumeAfterSeek = isPlaying)
            DesktopPlayerCommand.Stop -> stopMainPlayer()
            DesktopPlayerCommand.SeekStart -> seekMainPlayer(0L, resumeAfterSeek = isPlaying)
            DesktopPlayerCommand.SeekEnd -> if (duration > 0L) seekMainPlayer(duration - 500L, resumeAfterSeek = false)
            null -> Unit
        }
    }

    fun openDictionaryLookup(query: String) {
        if (isPlaying) pauseMainPlayer()
        showSubtitleMenu = false
        showDictQuery = query
    }

    val subtitleLookupClickState = rememberUpdatedState(
        newValue = { text: String, offset: Int ->
            scope.launch {
                val selection = dictionaryEngine.resolveSubtitleLookupSelection(
                    text = text,
                    offset = offset,
                    targetLanguage = dictionaryEngine.getTargetLanguage()
                ) ?: return@launch
                openDictionaryLookup(selection.query)
            }
        }
    )
    val subtitleOverlay = remember {
        SubtitleVideoOverlay { text, offset -> subtitleLookupClickState.value(text, offset) }
    }

    DisposableEffect(vlcPlayer, subtitleOverlay) {
        runCatching {
            vlcPlayer.overlay().set(subtitleOverlay)
            vlcPlayer.overlay().enable(false)
        }.onFailure {
            CrashReporter.recordHandled("PlayerScreen.installSubtitleOverlay", it)
        }
        onDispose {
            runCatching { vlcPlayer.overlay().enable(false) }
            subtitleOverlay.clearSubtitle()
            subtitleOverlay.dispose()
        }
    }

    fun startRecordingToFile(file: File): Boolean {
        val nextRecorder = ShadowAudioRecorder(dictionaryEngine.isNoiseCancellationEnabled())
        val started = nextRecorder.start(file)
        if (started) {
            recorder = nextRecorder
            tempTargetFile = file
            isRecording = true
        }
        return started
    }

    fun stopRecordingSafe(target: File?) {
        val rec = recorder ?: return
        val file = target ?: File(WindowsPaths.recordingsDir, "Shadowing_${System.currentTimeMillis()}.m4a")
        val endTime = currentPos
        scope.launch {
            val saved = withContext(Dispatchers.IO) { rec.stop(file) }
            isRecording = false
            recorder = null
            tempTargetFile = null
            pauseMainPlayer()
            if (saved != null && endTime - recordStartTime > 400L) {
                val recording = ShadowRecording(filePath = saved.absolutePath, startTime = recordStartTime, endTime = endTime)
                recordings.add(0, recording)
                syncWithStorage()
            } else {
                saved?.delete()
            }
        }
    }

    fun stopVoicePlayback() {
        runCatching { voicePlayer.controls().stop() }
        activeVoiceSegmentId = null
        voiceCurrentPos = -1L
    }

    fun playVoiceSegment(rec: ShadowRecording, startAtMs: Long = 0L) {
        runCatching { voicePlayer.controls().stop() }
        runCatching {
            voicePlayer.media().play(rec.filePath)
            activeVoiceSegmentId = rec.id
            val target = startAtMs.coerceAtLeast(0L).coerceAtMost((rec.endTime - rec.startTime).coerceAtLeast(0L))
            if (target > 0L) voicePlayer.controls().setTime(target)
            voiceCurrentPos = target
        }.onFailure {
            activeVoiceSegmentId = null
            voiceCurrentPos = -1L
        }
    }

    fun toggleVoiceSegment(rec: ShadowRecording) {
        if (activeVoiceSegmentId == rec.id) {
            val playing = runCatching { voicePlayer.status().isPlaying }.getOrDefault(false)
            if (playing) {
                runCatching { voicePlayer.controls().pause() }
            } else {
                runCatching { voicePlayer.controls().play() }
            }
        } else {
            playVoiceSegment(rec)
        }
    }

    fun seekVoiceSegment(rec: ShadowRecording, positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L).coerceAtMost((rec.endTime - rec.startTime).coerceAtLeast(0L))
        if (activeVoiceSegmentId == rec.id) {
            runCatching {
                voicePlayer.controls().setTime(target)
                if (!voicePlayer.status().isPlaying) voicePlayer.controls().play()
                voiceCurrentPos = target
            }.onFailure {
                playVoiceSegment(rec, target)
            }
        } else {
            playVoiceSegment(rec, target)
        }
    }

    fun toggleRepeatPractice(segment: ShadowRecording, collapseBacklogOnStart: Boolean = false) {
        if (repeatPracticeSegment?.id == segment.id) {
            repeatPracticeSegment = null
            repeatPracticePhase = RepeatPracticePhase.Idle
        } else {
            activeOriginalSegment = null
            repeatAttemptCount = 0
            repeatPracticePhase = RepeatPracticePhase.Idle
            repeatPracticeSegment = segment
            if (collapseBacklogOnStart) showBacklog = false
        }
    }

    fun currentEmbeddedSubtitleTracks(): List<TrackDescription> =
        runCatching { vlcPlayer.subpictures().trackDescriptions().orEmpty() }.getOrDefault(emptyList())

    fun syncEmbeddedSubtitleState(subtitleTracks: List<TrackDescription>) {
        val currentTrackId = runCatching { vlcPlayer.subpictures().track() }.getOrDefault(-1)
        if (currentTrackId != -1) {
            selectedEmbeddedSubtitleTrackId = currentTrackId
            embeddedSubtitlesEnabled = true
        } else if (libraryItem.subtitleUri != null) {
            embeddedSubtitlesEnabled = isSubtitlesVisible
        } else if (subtitleTracks.any { it.id() != -1 }) {
            embeddedSubtitlesEnabled = false
        }
    }

    fun attachExternalSubtitleFile() {
        val subtitlePath = libraryItem.subtitleUri ?: return
        val subtitleFile = localMediaFile(subtitlePath)
        if (subtitleFile.isFile) {
            runCatching {
                vlcPlayer.subpictures().setSubTitleFile(subtitleFile)
            }
        }
    }

    fun setEmbeddedSubtitlesEnabled(enabled: Boolean, subtitleTracks: List<TrackDescription>) {
        if (enabled) {
            val trackId = selectedEmbeddedSubtitleTrackId
                .takeIf { id -> id != -1 && subtitleTracks.any { it.id() == id } }
                ?: subtitleTracks.firstOrNull { it.id() != -1 }?.id()
            if (trackId != null) {
                runCatching { vlcPlayer.subpictures().setTrack(trackId) }
                selectedEmbeddedSubtitleTrackId = trackId
            } else {
                attachExternalSubtitleFile()
            }
            isSubtitlesVisible = true
            embeddedSubtitlesEnabled = true
        } else {
            runCatching { vlcPlayer.subpictures().setTrack(-1) }
            isSubtitlesVisible = false
            embeddedSubtitlesEnabled = false
        }
    }

    fun setSubtitleVisibility(visible: Boolean, subtitleTracks: List<TrackDescription> = currentEmbeddedSubtitleTracks()) {
        if (visible) {
            isSubtitlesVisible = true
            if (subtitleTracks.any { it.id() != -1 } || libraryItem.subtitleUri != null) {
                setEmbeddedSubtitlesEnabled(true, subtitleTracks)
            }
        } else {
            isSubtitlesVisible = false
            runCatching { vlcPlayer.subpictures().setTrack(-1) }
            embeddedSubtitlesEnabled = false
        }
    }

    LaunchedEffect(showSubtitleMenu) {
        if (showSubtitleMenu) {
            embeddedSubtitleTracks = currentEmbeddedSubtitleTracks()
            syncEmbeddedSubtitleState(embeddedSubtitleTracks)
        }
    }

    LaunchedEffect(showSubtitleMenu, libraryItem.sourceUrl) {
        val sourceUrl = libraryItem.sourceUrl?.takeIf { isYoutubeUrl(it) }
        if (showSubtitleMenu && sourceUrl != null && !youtubeSubtitleChoicesLoaded && !isLoadingYoutubeSubtitleChoices) {
            isLoadingYoutubeSubtitleChoices = true
            try {
                youtubeSubtitleChoices = withContext(Dispatchers.IO) {
                    runCatching { fetchYoutubeSubtitleChoices(sourceUrl) }.getOrDefault(emptyList())
                }
                youtubeSubtitleChoicesLoaded = true
            } finally {
                isLoadingYoutubeSubtitleChoices = false
            }
        }
    }

    LaunchedEffect(libraryItem.id, actualMediaUri) {
        val media = actualMediaUri ?: return@LaunchedEffect
        cacheResolvedStreamUrl(libraryItem.sourceUrl, libraryItem.formatId, media)
        runCatching {
            vlcPlayer.media().prepare(media, *vlcMediaOptions(libraryItem))
            if (libraryItem.progress > 0L) vlcPlayer.controls().setTime(libraryItem.progress)
            attachExternalSubtitleFile()
        }
    }

    LaunchedEffect(libraryItem.sourceUrl, actualMediaUri) {
        val sourceUrl = libraryItem.sourceUrl
        if (actualMediaUri == null && sourceUrl != null) {
            cachedPlayableMediaUri(libraryItem)?.let { cached ->
                actualMediaUri = cached
                if (playWhenStreamReady) {
                    playWhenStreamReady = false
                    runCatching {
                        isOpeningMedia = true
                        vlcPlayer.media().play(cached, *vlcMediaOptions(libraryItem))
                        if (currentPos > 0L) vlcPlayer.controls().setTime(currentPos)
                        isPlaying = true
                        playbackStatusMessage = null
                    }.onFailure {
                        CrashReporter.recordHandled("PlayerScreen.playCachedYoutubeStream", it)
                        isOpeningMedia = false
                        isPlaying = false
                        playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
                    }
                }
                return@LaunchedEffect
            }
            isRefreshingStream = true
            val resolvedStream = withContext(Dispatchers.IO) {
                resolveYoutubeMediaStream(sourceUrl, libraryItem.formatId, preferredYoutubeResolution())
            }
            val playbackItem = resolvedStream?.let { applyResolvedMediaStream(it) }
            if (resolvedStream != null && playbackStatusMessage == "Resolving live stream URL...") {
                playbackStatusMessage = null
            }
            if (resolvedStream != null && playWhenStreamReady) {
                playWhenStreamReady = false
                runCatching {
                    isOpeningMedia = true
                    vlcPlayer.media().play(resolvedStream.url, *vlcMediaOptions(playbackItem ?: libraryItem))
                    if (currentPos > 0L) vlcPlayer.controls().setTime(currentPos)
                    isPlaying = true
                }.onFailure {
                    CrashReporter.recordHandled("PlayerScreen.playResolvedYoutubeStream", it)
                    isOpeningMedia = false
                    isPlaying = false
                    playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
                }
            } else if (resolvedStream == null) {
                playbackStatusMessage = "Playback failed. Stream might be geo-blocked or broken."
                isOpeningMedia = false
                isPlaying = false
            }
            isRefreshingStream = false
        }
    }

    DisposableEffect(vlcPlayer, libraryItem.id) {
        val listener = object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) {
                isOpeningMedia = true
                playbackStatusMessage = null
            }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (!runCatching { mediaPlayer.status().isPlaying }.getOrDefault(false) && newCache < 100f) {
                    isOpeningMedia = true
                }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                isOpeningMedia = false
                refreshStreamAfterPlaybackError()
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                playbackStatusMessage = null
                isOpeningMedia = false
                isPlaying = true
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                isOpeningMedia = false
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                isOpeningMedia = false
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                isOpeningMedia = false
            }
        }
        vlcPlayer.events().addMediaPlayerEventListener(listener)
        onDispose { vlcPlayer.events().removeMediaPlayerEventListener(listener) }
    }

    LaunchedEffect(libraryItem.id, libraryItem.subtitleUri, libraryItem.sourceUrl, actualMediaUri, isRefreshingStream) {
        val sourceUrl = libraryItem.sourceUrl
        if (
            !autoSubtitleRetryAttempted &&
            libraryItem.subtitleUri == null &&
            sourceUrl != null &&
            isYoutubeUrl(sourceUrl) &&
            prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true) &&
            (actualMediaUri != null || !isRefreshingStream)
        ) {
            autoSubtitleRetryAttempted = true
            isParsingSubtitles = true
            val preferredLanguage = prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE)
                ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE
            val choices = withContext(Dispatchers.IO) {
                runCatching { fetchYoutubeSubtitleChoices(sourceUrl) }.getOrDefault(emptyList())
            }
            youtubeSubtitleChoices = choices
            youtubeSubtitleChoicesLoaded = true
            val subtitlePath = if (choices.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    downloadPreferredYoutubeSubtitle(sourceUrl, choices, preferredLanguage)
                }
            } else {
                null
            }
            if (subtitlePath != null) {
                libraryItem = libraryItem.copy(subtitleUri = subtitlePath)
                isSubtitlesVisible = true
                syncWithStorage()
            } else {
                isParsingSubtitles = false
            }
        }
    }

    LaunchedEffect(libraryItem.subtitleUri) {
        if (libraryItem.subtitleUri != null) {
            isParsingSubtitles = true
            attachExternalSubtitleFile()
            parsedCues = withContext(Dispatchers.IO) {
                parseSimpleSubtitles(libraryItem.subtitleUri!!)
            }
            isParsingSubtitles = false
        } else {
            parsedCues = emptyList()
            isParsingSubtitles = false
        }
    }

    LaunchedEffect(currentPos, parsedCues, isSubtitlesVisible, subtitleDelayMs, isParsingSubtitles, libraryItem.subtitleUri) {
        currentSubtitleText = subtitleTextForPlayback(
            cues = parsedCues,
            currentPosMs = currentPos,
            subtitleDelayMs = subtitleDelayMs,
            isSubtitlesVisible = isSubtitlesVisible,
            isParsingSubtitles = isParsingSubtitles,
            hasSubtitleFile = libraryItem.subtitleUri != null
        )
    }

    LaunchedEffect(libraryItem.isVideo, isSubtitlesVisible, currentSubtitleText, showSubtitleMenu) {
        val shouldShowOverlay = libraryItem.isVideo && isSubtitlesVisible && currentSubtitleText.isNotBlank()
        subtitleOverlay.updateSubtitle(
            text = currentSubtitleText,
            visible = shouldShowOverlay,
            lookupEnabled = !showSubtitleMenu
        )
        runCatching {
            vlcPlayer.overlay().enable(shouldShowOverlay)
        }.onFailure {
            CrashReporter.recordHandled("PlayerScreen.updateSubtitleOverlay", it)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            val playerTime = runCatching { vlcPlayer.status().time() }.getOrDefault(currentPos)
            val playerLength = runCatching { vlcPlayer.status().length() }.getOrDefault(duration)
            val playerPlaying = runCatching { vlcPlayer.status().isPlaying }.getOrDefault(false)
            if (playerTime >= 0L) currentPos = playerTime
            if (playerLength > 0L) duration = playerLength
            isPlaying = playerPlaying
            if (duration > 0L && currentPos >= duration - 350L && playerPlaying.not()) hasReachedEnd = true
            if (currentPos / 5000L != libraryItem.progress / 5000L) syncWithStorage()
            if (activeVoiceSegmentId != null) {
                val voiceTime = runCatching { voicePlayer.status().time() }.getOrDefault(voiceCurrentPos)
                val voiceLength = runCatching { voicePlayer.status().length() }.getOrDefault(0L)
                val voicePlaying = runCatching { voicePlayer.status().isPlaying }.getOrDefault(false)
                if (voiceTime >= 0L) voiceCurrentPos = voiceTime
                if (!voicePlaying && voiceLength > 0L && voiceTime >= voiceLength - 250L) {
                    activeVoiceSegmentId = null
                    voiceCurrentPos = -1L
                }
            }
        }
    }

    LaunchedEffect(activeOriginalSegment) {
        val segment = activeOriginalSegment ?: return@LaunchedEffect
        seekMainPlayer(segment.startTime, resumeAfterSeek = true)
        while (activeOriginalSegment?.id == segment.id && currentPos < segment.endTime) delay(100)
        if (activeOriginalSegment?.id == segment.id) {
            pauseMainPlayer()
            activeOriginalSegment = null
        }
    }

    LaunchedEffect(repeatPracticeSegment) {
        val segment = repeatPracticeSegment ?: return@LaunchedEffect
        while (repeatPracticeSegment?.id == segment.id) {
            repeatPracticePhase = RepeatPracticePhase.RecordingAttempt
            val file = File(WindowsPaths.recordingsDir, "Repeat_${System.currentTimeMillis()}.m4a")
            recordStartTime = segment.startTime
            seekMainPlayer(segment.startTime, resumeAfterSeek = true)
            if (startRecordingToFile(file)) {
                while (repeatPracticeSegment?.id == segment.id && currentPos < segment.endTime) delay(80)
                val rec = recorder
                val saved = withContext(Dispatchers.IO) { rec?.stop(file) }
                isRecording = false
                recorder = null
                pauseMainPlayer()
                if (saved != null && saveRepeatRecordings) {
                    recordings.add(0, ShadowRecording(filePath = saved.absolutePath, startTime = segment.startTime, endTime = segment.endTime))
                    syncWithStorage()
                }
                repeatPracticePhase = RepeatPracticePhase.PlayingAttempt
                if (saved != null) {
                    runCatching { voicePlayer.media().play(saved.absolutePath) }
                    while (repeatPracticeSegment?.id == segment.id && runCatching { voicePlayer.status().isPlaying }.getOrDefault(false)) delay(100)
                    if (!saveRepeatRecordings) saved.delete()
                }
                repeatAttemptCount += 1
            } else {
                repeatPracticeSegment = null
            }
            repeatPracticePhase = RepeatPracticePhase.Idle
            delay(300)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            syncWithStorage()
            runCatching { vlcPlayer.overlay().enable(false) }
            subtitleOverlay.clearSubtitle()
            runCatching { recorder?.cancel() }
            runCatching { voicePlayer.release() }
            runCatching { voiceFactory.release() }
            runCatching { vlcComponent.release() }
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    syncWithStorage()
                    onBack(libraryItem)
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    libraryItem.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (libraryItem.subtitleUri != null || libraryItem.isVideo) {
                    IconButton(onClick = {
                        showDictQuery = null
                        showSubtitleMenu = true
                    }) {
                        Icon(
                            Icons.Default.ClosedCaption,
                            contentDescription = "Subtitles",
                            tint = if (isSubtitlesVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(Modifier.weight(0.45f).fillMaxWidth().background(Color.Black)) {
                if (libraryItem.isVideo) {
                    SwingPanel(modifier = Modifier.fillMaxSize(), factory = { vlcComponent })
                } else {
                    if (albumArt != null) {
                        Image(albumArt, contentDescription = "Album Art", contentScale = if (isSubtitlesVisible) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize().then(if (isSubtitlesVisible) Modifier.blur(24.dp) else Modifier))
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF1E1E24)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(80.dp))
                        }
                    }
                }
                if (!libraryItem.isVideo && isSubtitlesVisible && currentSubtitleText.isNotBlank()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        ClickableSubtitleText(
                            text = currentSubtitleText,
                            targetLanguage = dictionaryEngine.getTargetLanguage(),
                            onWordClicked = { openDictionaryLookup(it) },
                            resolveLookupSelection = { source, offset, language ->
                                dictionaryEngine.resolveSubtitleLookupSelection(source, offset, language)
                            }
                        )
                    }
                }
                if (isRefreshingStream) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(Modifier.width(220.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Resolving live stream URL...", color = Color.White)
                        }
                    }
                }
                if (isOpeningMedia && !isPlaying && !isRefreshingStream) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(Modifier.width(220.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Loading video...", color = Color.White)
                        }
                    }
                }
                playbackStatusMessage?.let { message ->
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)), contentAlignment = Alignment.Center) {
                        Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                    }
                }
            }

            Box(
                Modifier.weight(0.55f).fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(horizontal = 16.dp)
            ) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime(currentPos), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Slider(value = currentPos.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)), onValueChange = { seekMainPlayer(it.toLong(), resumeAfterSeek = isPlaying) }, enabled = !isRecording && repeatPracticeSegment == null, valueRange = 0f..duration.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        if (!isRecording && repeatPracticeSegment == null) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { seekMainPlayer(currentPos - skipDurationMs, resumeAfterSeek = isPlaying) }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.FastRewind, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                                }
                                Box(Modifier.size(52.dp).clip(CircleShape).background(if (isRefreshingStream) Color.DarkGray else MaterialTheme.colorScheme.primary).clickable(enabled = !isRefreshingStream) {
                                    if (isPlaying || isOpeningMedia) pauseMainPlayer() else playMainPlayer()
                                }, contentAlignment = Alignment.Center) {
                                    Icon(if (isPlaying || isOpeningMedia) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                                }
                                IconButton(onClick = { seekMainPlayer(currentPos + skipDurationMs, resumeAfterSeek = isPlaying) }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.FastForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                        if (isSubtitlesVisible) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Subtitle Delay", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        subtitleDelayMs = (subtitleDelayMs - 250L).coerceIn(-5000L, 5000L)
                                        prefs.putLong(PREF_SUBTITLE_OFFSET_MS, subtitleDelayMs)
                                    }) { Text("-0.25s", fontSize = 13.sp) }
                                    Text("%.2fs".format(subtitleDelayMs / 1000f), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    TextButton(onClick = {
                                        subtitleDelayMs = (subtitleDelayMs + 250L).coerceIn(-5000L, 5000L)
                                        prefs.putLong(PREF_SUBTITLE_OFFSET_MS, subtitleDelayMs)
                                    }) { Text("+0.25s", fontSize = 13.sp) }
                                }
                            }
                        }
                        repeatPracticeSegment?.let { segment ->
                            val repeatStatus = when (repeatPracticePhase) {
                                RepeatPracticePhase.RecordingAttempt -> "Recording with source"
                                RepeatPracticePhase.PlayingAttempt -> "Playing attempt"
                                RepeatPracticePhase.Idle -> "Starting"
                            }
                            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Repeating ${formatTime(segment.startTime)} - ${formatTime(segment.endTime)}  Attempts: $repeatAttemptCount", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        TextButton(onClick = {
                                            repeatPracticeSegment = null
                                            repeatPracticePhase = RepeatPracticePhase.Idle
                                        }) { Text("Stop", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold) }
                                    }
                                    Text(repeatStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Save repeat recordings", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        Switch(checked = saveRepeatRecordings, onCheckedChange = {
                                            saveRepeatRecordings = it
                                            prefs.putBoolean(PREF_SAVE_REPEAT_RECORDINGS, it)
                                        })
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                if (repeatPracticeSegment != null) {
                                    repeatPracticeSegment = null
                                    repeatPracticePhase = RepeatPracticePhase.Idle
                                } else if (isRecording) {
                                    stopRecordingSafe(tempTargetFile)
                                } else {
                                    val file = File(WindowsPaths.recordingsDir, "Shadowing_${System.currentTimeMillis()}.m4a")
                                    recordStartTime = if (hasReachedEnd || (duration > 0L && currentPos >= duration - 500L)) 0L else currentPos
                                    if (startRecordingToFile(file)) playMainPlayer()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording || repeatPracticeSegment != null) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                                contentColor = if (isRecording || repeatPracticeSegment != null) Color.White else MaterialTheme.colorScheme.onPrimary
                            ),
                            enabled = isRecording || repeatPracticeSegment != null || (!isRefreshingStream && !isOpeningMedia)
                        ) {
                            Icon(if (isRecording || repeatPracticeSegment != null) Icons.Default.Stop else Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(when {
                                repeatPracticeSegment != null -> "STOP REPEAT MODE"
                                isRecording -> "STOP SHADOWING"
                                else -> "START SHADOWING"
                            }, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    if (recordings.isNotEmpty() && !isRecording && repeatPracticeSegment == null) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Latest Recording", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                if (recordings.size > 1) {
                                    TextButton(onClick = { showBacklog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                                        Text("View Backlog (${recordings.size - 1})", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                    }
                                }
                            }
                            val latest = recordings.first()
                            RecordingItemCard(
                                rec = latest,
                                originalMediaUri = actualMediaUri ?: libraryItem.mediaUri,
                                onPlayOriginal = { activeOriginalSegment = if (activeOriginalSegment?.id == latest.id) null else latest },
                                onPlayVoice = { toggleVoiceSegment(latest) },
                                onSeekOriginal = { seekMainPlayer(it, resumeAfterSeek = false) },
                                onSeekVoice = { seekVoiceSegment(latest, it) },
                                onRepeatPractice = { toggleRepeatPractice(latest) },
                                onDelete = {
                                    if (activeVoiceSegmentId == latest.id) stopVoicePlayback()
                                    File(latest.filePath).delete()
                                    recordings.remove(latest)
                                    syncWithStorage()
                                },
                                onShare = { exportRecording(File(latest.filePath)) },
                                isRepeatPracticeActive = repeatPracticeSegment?.id == latest.id,
                                currentOriginalTime = currentPos,
                                currentRecordedTime = if (activeVoiceSegmentId == latest.id) voiceCurrentPos else -1L,
                                isOriginalPlaying = activeOriginalSegment?.id == latest.id,
                                isRecordedPlaying = activeVoiceSegmentId == latest.id
                            )
                        }
                    }
                }
                if (showSubtitleMenu) {
                    val youtubeSourceUrl = libraryItem.sourceUrl?.takeIf { isYoutubeUrl(it) }
                    val visibleTracks = embeddedSubtitleTracks.filter { it.id() != -1 }
                    val subtitleMenuListState = rememberLazyListState()
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .fillMaxWidth(0.58f)
                            .heightIn(max = 430.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        shadowElevation = 10.dp,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Subtitles", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showSubtitleMenu = false }) { Text("Done") }
                            }
                            Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                                LazyColumn(
                                    state = subtitleMenuListState,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(end = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                item {
                                    SubtitleOptionRow(if (isSubtitlesVisible) "Hide Captions" else "Show Captions") {
                                        setSubtitleVisibility(!isSubtitlesVisible, embeddedSubtitleTracks)
                                        showSubtitleMenu = false
                                    }
                                }
                                item {
                                    SubtitleOptionRow("Add Custom Subtitles") {
                                        showSubtitleMenu = false
                                        val subtitle = openSingleFileDialog("Add Custom Subtitles", listOf(".srt", ".vtt", ".ass"))
                                        if (subtitle != null) {
                                            val copied = copyIntoAppSubtitles(subtitle)
                                            if (copied != null) {
                                                libraryItem = libraryItem.copy(subtitleUri = copied)
                                                isSubtitlesVisible = true
                                                embeddedSubtitlesEnabled = true
                                                selectedYoutubeSubtitleKey = null
                                                parsedCues = parseSimpleSubtitles(copied)
                                                isParsingSubtitles = false
                                                syncWithStorage()
                                            }
                                        }
                                    }
                                }

                                if (youtubeSourceUrl != null) {
                                    item {
                                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                        Text("YouTube Captions", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (isLoadingYoutubeSubtitleChoices) {
                                        item { SubtitleOptionRow("Loading YouTube captions...", enabled = false) {} }
                                    } else if (youtubeSubtitleChoices.isEmpty() && youtubeSubtitleChoicesLoaded) {
                                        item { SubtitleOptionRow("No YouTube captions", enabled = false) {} }
                                    } else {
                                        items(youtubeSubtitleChoices) { choice ->
                                            val choiceKey = "${choice.languageCode}:${choice.isAutoGenerated}"
                                            SubtitleOptionRow(choice.label, selected = selectedYoutubeSubtitleKey == choiceKey) {
                                                showSubtitleMenu = false
                                                selectedYoutubeSubtitleKey = choiceKey
                                                scope.launch {
                                                    isParsingSubtitles = true
                                                    val downloaded = withContext(Dispatchers.IO) {
                                                        downloadYoutubeSubtitle(youtubeSourceUrl, choice.languageCode, choice.isAutoGenerated)
                                                    }
                                                    if (downloaded != null) {
                                                        libraryItem = libraryItem.copy(subtitleUri = downloaded)
                                                        isSubtitlesVisible = true
                                                        embeddedSubtitlesEnabled = true
                                                        parsedCues = parseSimpleSubtitles(downloaded)
                                                        isParsingSubtitles = false
                                                        syncWithStorage()
                                                    } else {
                                                        currentSubtitleText = "Subtitle download failed"
                                                        isParsingSubtitles = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    Text("Embedded Captions", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                if (visibleTracks.isEmpty()) {
                                    item { SubtitleOptionRow("No embedded captions", enabled = false) {} }
                                } else {
                                    items(visibleTracks) { track ->
                                        SubtitleOptionRow(track.description(), selected = selectedEmbeddedSubtitleTrackId == track.id()) {
                                            runCatching { vlcPlayer.subpictures().setTrack(track.id()) }
                                            selectedEmbeddedSubtitleTrackId = track.id()
                                            isSubtitlesVisible = true
                                            embeddedSubtitlesEnabled = true
                                            showSubtitleMenu = false
                                        }
                                    }
                                }
                                item {
                                    SubtitleOptionRow(if (embeddedSubtitlesEnabled) "Disable Embedded Subs" else "Enable Embedded Subs") {
                                        setEmbeddedSubtitlesEnabled(!embeddedSubtitlesEnabled, embeddedSubtitleTracks)
                                        showSubtitleMenu = false
                                    }
                                }
                            }
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(subtitleMenuListState),
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }
                }
                showDictQuery?.let { query ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .fillMaxWidth(0.86f)
                            .heightIn(max = 430.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        shadowElevation = 10.dp,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        HoshiDictionaryPanel(query = query, engine = dictionaryEngine, onDismiss = { showDictQuery = null })
                    }
                }
            }
        }
    }

    if (showBacklog) {
        ModalBottomSheet(onDismissRequest = { showBacklog = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
                Text("Session Backlog", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recordings.drop(1), key = { it.id }) { rec ->
                        RecordingItemCard(
                            rec = rec,
                            originalMediaUri = actualMediaUri ?: libraryItem.mediaUri,
                            onPlayOriginal = { activeOriginalSegment = if (activeOriginalSegment?.id == rec.id) null else rec },
                            onPlayVoice = { toggleVoiceSegment(rec) },
                            onSeekOriginal = { seekMainPlayer(it, resumeAfterSeek = false) },
                            onSeekVoice = { seekVoiceSegment(rec, it) },
                            onRepeatPractice = { toggleRepeatPractice(rec, collapseBacklogOnStart = true) },
                            onDelete = {
                                if (activeVoiceSegmentId == rec.id) stopVoicePlayback()
                                File(rec.filePath).delete()
                                recordings.remove(rec)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(File(rec.filePath)) },
                            isRepeatPracticeActive = repeatPracticeSegment?.id == rec.id,
                            currentOriginalTime = currentPos,
                            currentRecordedTime = if (activeVoiceSegmentId == rec.id) voiceCurrentPos else -1L,
                            isOriginalPlaying = activeOriginalSegment?.id == rec.id,
                            isRecordedPlaying = activeVoiceSegmentId == rec.id
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleOptionRow(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = contentColor, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun configureVlcNativePath() {
    val vlcDir = MediaTools.vlcNativeDir() ?: return
    System.setProperty("jna.library.path", vlcDir.absolutePath)
    System.setProperty("VLC_PLUGIN_PATH", File(vlcDir, "plugins").absolutePath)
    val currentPath = System.getProperty("java.library.path").orEmpty()
    if (!currentPath.split(File.pathSeparator).contains(vlcDir.absolutePath)) {
        System.setProperty("java.library.path", currentPath + File.pathSeparator + vlcDir.absolutePath)
    }
}

private fun vlcMediaOptions(item: LibraryItem): Array<String> = buildList {
    val youtubeStream = item.sourceUrl?.let { isYoutubeUrl(it) } == true
    add(":network-caching=${if (youtubeStream) 350 else 800}")
    add(":file-caching=${if (youtubeStream) 150 else 300}")
    add(":http-reconnect")
    val userAgent = item.httpUserAgent?.takeIf { it.isNotBlank() }
        ?: if (youtubeStream) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36" else null
    val referer = item.httpReferer?.takeIf { it.isNotBlank() }
        ?: if (youtubeStream) "https://www.youtube.com/" else null
    userAgent?.let { add(":http-user-agent=$it") }
    referer?.let { add(":http-referrer=$it") }
}.toTypedArray()
