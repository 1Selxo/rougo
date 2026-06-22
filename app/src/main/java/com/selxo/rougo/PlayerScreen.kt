package com.selxo.rougo

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.MediaPlayer as AndroidMediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VLCMedia
import org.videolan.libvlc.MediaPlayer as VLCMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private enum class RepeatPracticePhase { Idle, RecordingAttempt, PlayingAttempt }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(initialLibraryItem: LibraryItem, onBack: (LibraryItem) -> Unit) {
    var libraryItem by remember { mutableStateOf(initialLibraryItem) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val dictionaryEngine = remember { DictionaryEngine.getInstance(context) }
    var showDictQuery by remember { mutableStateOf<String?>(null) }
    var resumePlaybackAfterDictionaryDismiss by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    val skipSeconds = remember { prefs.getInt(PREF_SKIP_SECONDS, DEFAULT_SKIP_SECONDS).coerceIn(1, 30) }
    val skipDurationMs = skipSeconds * 1000L

    var isPlaying by remember { mutableStateOf(false) }
    var hasReachedEnd by remember { mutableStateOf(false) }
    var isPlayerNotificationVisible by remember { mutableStateOf(true) }
    var currentPos by remember { mutableLongStateOf(libraryItem.progress) }
    var duration by remember { mutableLongStateOf(libraryItem.duration) }
    var isSubtitlesVisible by remember { mutableStateOf(libraryItem.subtitleUri != null || libraryItem.isVideo) }
    var subtitleDelayMs by remember {
        mutableLongStateOf(prefs.getLong(PREF_SUBTITLE_OFFSET_MS, DEFAULT_SUBTITLE_OFFSET_MS).coerceIn(-5000L, 5000L))
    }

    var parsedAudioCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var isParsingSubtitles by remember { mutableStateOf(libraryItem.subtitleUri != null) }
    var currentSubtitleText by remember { mutableStateOf("") }
    var youtubeSubtitleChoices by remember(libraryItem.id) { mutableStateOf<List<YoutubeSubtitleChoice>>(emptyList()) }
    var isLoadingYoutubeSubtitleChoices by remember(libraryItem.id) { mutableStateOf(false) }
    var youtubeSubtitleChoicesLoaded by remember(libraryItem.id) { mutableStateOf(false) }
    var selectedYoutubeSubtitleKey by remember(libraryItem.id) { mutableStateOf<String?>(null) }
    var autoSubtitleRetryAttempted by remember(libraryItem.id) { mutableStateOf(false) }

    val albumArt = loadAlbumArt(
        context = context,
        uriString = libraryItem.mediaUri,
        isVideo = libraryItem.isVideo,
        cachedCoverPath = libraryItem.coverArtPath,
        itemId = libraryItem.id,
        sourceUrl = libraryItem.sourceUrl
    )

    var isRecording by remember { mutableStateOf(false) }
    var shadowAudioRecorder by remember { mutableStateOf<ShadowAudioRecorder?>(null) }
    var recordStartTime by remember { mutableLongStateOf(0L) }

    val recordings = remember { mutableStateListOf<ShadowRecording>().apply { addAll(libraryItem.recordings) } }
    var activeOriginalSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var repeatPracticeSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var repeatAttemptCount by remember { mutableIntStateOf(0) }
    var repeatPracticePhase by remember { mutableStateOf(RepeatPracticePhase.Idle) }
    var saveRepeatRecordings by remember {
        mutableStateOf(prefs.getBoolean(PREF_SAVE_REPEAT_RECORDINGS, true))
    }
    val latestSaveRepeatRecordings = rememberUpdatedState(saveRepeatRecordings)
    val temporaryRepeatAttemptPaths = remember { mutableStateListOf<String>() }

    var showBacklog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var embeddedSubtitlesEnabled by remember(libraryItem.id) { mutableStateOf(libraryItem.subtitleUri != null) }
    var selectedEmbeddedSubtitleTrackId by remember(libraryItem.id) { mutableIntStateOf(-1) }

    val initialPlayableUri = remember(libraryItem.id) { initialPlayableMediaUri(libraryItem) }
    var actualMediaUri by remember(libraryItem.id) { mutableStateOf(initialPlayableUri) }
    var isRefreshingStream by remember(libraryItem.id) { mutableStateOf(libraryItem.sourceUrl != null && initialPlayableUri == null) }
    var streamRefreshAttempts by remember(libraryItem.id) { mutableIntStateOf(0) }
    var resumePlaybackAfterPause by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val libVlc = remember { VLCManager.getLibVLC(context) }
    val vlcPlayer = remember { VLCMediaPlayer(libVlc) }
    val mediaSession = remember {
        MediaSession(context.applicationContext, "RougoPlayer").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
    }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun seekMainPlayer(positionMs: Long, resumeAfterSeek: Boolean = isPlaying) {
        val upperBound = if (duration > 0L) (duration - 500L).coerceAtLeast(0L) else Long.MAX_VALUE
        val nextPosition = positionMs.coerceIn(0L, upperBound)
        try {
            if (hasReachedEnd) {
                vlcPlayer.stop()
                vlcPlayer.play()
                hasReachedEnd = false
            }
            vlcPlayer.time = nextPosition
            if (resumeAfterSeek) {
                vlcPlayer.play()
            } else if (!isRecording) {
                vlcPlayer.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPos = nextPosition
    }

    fun playMainPlayer() {
        try {
            isPlayerNotificationVisible = true
            if (hasReachedEnd || (duration > 0L && currentPos >= duration - 500L)) {
                vlcPlayer.stop()
                vlcPlayer.play()
                hasReachedEnd = false
                vlcPlayer.time = 0L
                currentPos = 0L
            } else {
                vlcPlayer.play()
            }
            isPlaying = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseMainPlayer() {
        try { vlcPlayer.pause() } catch (e: Exception) { e.printStackTrace() }
        isPlaying = false
    }

    fun rewindMainPlayerFromControls() {
        if (isRecording) return
        seekMainPlayer(vlcPlayer.time - skipDurationMs, resumeAfterSeek = vlcPlayer.isPlaying)
    }

    fun fastForwardMainPlayerFromControls() {
        if (isRecording) return
        seekMainPlayer(vlcPlayer.time + skipDurationMs, resumeAfterSeek = vlcPlayer.isPlaying)
    }

    fun stopMainPlayerFromControls() {
        try {
            vlcPlayer.pause()
            vlcPlayer.time = 0L
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPos = 0L
        isPlaying = false
        isPlayerNotificationVisible = false
        mediaSession.isActive = false
        cancelPlayerNotification(context)
    }

    fun openDictionaryLookup(query: String) {
        val shouldResumeAfterDismiss = isPlaying || vlcPlayer.isPlaying
        resumePlaybackAfterDictionaryDismiss = shouldResumeAfterDismiss
        showDictQuery = query
        if (shouldResumeAfterDismiss) {
            try { vlcPlayer.pause() } catch (e: Exception) {}
            isPlaying = false
        }
    }

    fun dismissDictionaryLookup() {
        val shouldResumeAfterDismiss = resumePlaybackAfterDictionaryDismiss
        showDictQuery = null
        resumePlaybackAfterDictionaryDismiss = false
        if (shouldResumeAfterDismiss && actualMediaUri != null && !vlcPlayer.isPlaying) {
            playMainPlayer()
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

    fun syncEmbeddedSubtitleState(spuTracks: Array<org.videolan.libvlc.MediaPlayer.TrackDescription>) {
        val currentTrackId = runCatching { vlcPlayer.spuTrack }.getOrDefault(-1)
        if (currentTrackId != -1) {
            selectedEmbeddedSubtitleTrackId = currentTrackId
            embeddedSubtitlesEnabled = true
        } else if (libraryItem.subtitleUri != null) {
            embeddedSubtitlesEnabled = isSubtitlesVisible
        } else if (spuTracks.any { it.id != -1 }) {
            embeddedSubtitlesEnabled = false
        }
    }

    fun setEmbeddedSubtitlesEnabled(
        enabled: Boolean,
        spuTracks: Array<org.videolan.libvlc.MediaPlayer.TrackDescription>
    ) {
        if (enabled) {
            val trackId = selectedEmbeddedSubtitleTrackId
                .takeIf { it != -1 && spuTracks.any { track -> track.id == it } }
                ?: spuTracks.firstOrNull { it.id != -1 }?.id
            if (trackId != null) {
                vlcPlayer.spuTrack = trackId
                selectedEmbeddedSubtitleTrackId = trackId
            }
            isSubtitlesVisible = true
            embeddedSubtitlesEnabled = true
        } else {
            vlcPlayer.spuTrack = -1
            isSubtitlesVisible = false
            embeddedSubtitlesEnabled = false
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPlayerNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(vlcPlayer, skipDurationMs, duration, isRecording) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PLAYER_PLAY_PAUSE -> {
                        if (isRecording) return
                        if (vlcPlayer.isPlaying) {
                            pauseMainPlayer()
                        } else {
                            playMainPlayer()
                        }
                    }
                    ACTION_PLAYER_REWIND -> {
                        rewindMainPlayerFromControls()
                    }
                    ACTION_PLAYER_FORWARD -> {
                        fastForwardMainPlayerFromControls()
                    }
                    ACTION_PLAYER_STOP -> {
                        stopMainPlayerFromControls()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            PLAYER_NOTIFICATION_ACTIONS.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            cancelPlayerNotification(context)
        }
    }

    DisposableEffect(mediaSession) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        mediaSession.setSessionActivity(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                flags
            )
        )
        mediaSession.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!isRecording && actualMediaUri != null) playMainPlayer()
                }

                override fun onPause() {
                    if (!isRecording) pauseMainPlayer()
                }

                override fun onStop() {
                    stopMainPlayerFromControls()
                }

                override fun onSeekTo(pos: Long) {
                    if (!isRecording) seekMainPlayer(pos, resumeAfterSeek = isPlaying)
                }

                override fun onRewind() {
                    rewindMainPlayerFromControls()
                }

                override fun onFastForward() {
                    fastForwardMainPlayerFromControls()
                }
            },
            Handler(Looper.getMainLooper())
        )

        onDispose {
            mediaSession.setCallback(null)
            mediaSession.isActive = false
        }
    }

    LaunchedEffect(
        isPlayerNotificationVisible,
        isPlaying,
        currentPos / 1000L,
        duration,
        actualMediaUri,
        libraryItem.title,
        libraryItem.artist,
        libraryItem.album,
        libraryItem.albumArtist,
        libraryItem.year,
        libraryItem.coverArtPath,
        skipSeconds
    ) {
        val notificationSubtitle = libraryItem.metadataSummary()
        val artwork = libraryItem.coverArtPath?.let { decodeSampledBitmapFile(it, maxSize = 512) }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, libraryItem.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, libraryItem.title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.coerceAtLeast(0L))
            .apply {
                notificationSubtitle?.let {
                    putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                    putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
                }
                artwork?.let {
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                    putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
                }
            }
            .build()
        mediaSession.setMetadata(metadata)

        val basePlaybackActions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_STOP
        val playbackActions = if (duration > 0L) {
            basePlaybackActions or PlaybackState.ACTION_SEEK_TO
        } else {
            basePlaybackActions
        }
        val playbackState = when {
            actualMediaUri == null || !isPlayerNotificationVisible -> PlaybackState.STATE_STOPPED
            isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(playbackActions)
                .setState(
                    playbackState,
                    currentPos.coerceAtLeast(0L),
                    if (isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
        mediaSession.isActive = actualMediaUri != null && isPlayerNotificationVisible

        if (isPlayerNotificationVisible && actualMediaUri != null) {
            showPlayerNotification(
                context = context,
                title = libraryItem.title,
                subtitle = notificationSubtitle,
                coverArtPath = libraryItem.coverArtPath,
                currentPos = currentPos,
                duration = duration,
                isPlaying = isPlaying,
                skipSeconds = skipSeconds,
                mediaSessionToken = mediaSession.sessionToken
            )
        } else {
            cancelPlayerNotification(context)
        }
    }

    DisposableEffect(lifecycleOwner, videoLayout, actualMediaUri) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (libraryItem.isVideo) {
                        resumePlaybackAfterPause = vlcPlayer.isPlaying
                        currentPos = vlcPlayer.time.coerceAtLeast(0)
                        try { vlcPlayer.pause() } catch (e: Exception) {}
                        try { vlcPlayer.detachViews() } catch (e: Exception) {}
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (libraryItem.isVideo && videoLayout != null && actualMediaUri != null) {
                        try {
                            vlcPlayer.detachViews()
                            vlcPlayer.attachViews(videoLayout!!, null, false, false)
                            if (currentPos > 0) vlcPlayer.time = currentPos
                            if (resumePlaybackAfterPause) vlcPlayer.play()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(libraryItem) {
        val currentSourceUrl = libraryItem.sourceUrl
        if (currentSourceUrl != null && actualMediaUri == null) {
            isRefreshingStream = true
            val resolvedUri = withContext(Dispatchers.IO) {
                try {
                    resolveYoutubeStreamUrl(context, currentSourceUrl, libraryItem.formatId)
                } catch (e: Exception) {
                    e.printStackTrace()
                    initialPlayableMediaUri(libraryItem)
                }
            }
            if (!resolvedUri.isNullOrBlank()) {
                actualMediaUri = resolvedUri
                libraryItem = libraryItem.copy(progress = currentPos)
                LibraryManager(context).saveItem(libraryItem)
            }
            isRefreshingStream = false
        }
    }

    fun playbackStorageItem(): LibraryItem {
        val decision = decidePlaybackStorageItem(
            item = libraryItem,
            progress = currentPos,
            duration = duration,
            recordings = recordings.toList(),
            actualMediaUri = actualMediaUri
        )
        if (decision.blockedRecordingMediaUri) {
            CrashReporter.recordHandled(
                context,
                "Recording persistence guard",
                IllegalStateException("Blocked recording media URI from replacing source media URI")
            )
        }
        return decision.item
    }

    fun syncWithStorage() {
        LibraryManager(context).saveItem(playbackStorageItem())
    }

    var voiceCurrentPos by remember { mutableLongStateOf(-1L) }
    val voiceAudioPlayer = remember {
        AndroidMediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
            )
        }
    }
    var activeVoiceSegmentId by remember { mutableStateOf<String?>(null) }

    fun deleteTemporaryRepeatAttempt(path: String) {
        if (path.isBlank()) return
        temporaryRepeatAttemptPaths.remove(path)
        try { File(path).delete() } catch (e: Exception) {}
    }

    fun stopVoicePlayback() {
        try { voiceAudioPlayer.stop() } catch (e: Exception) {}
        try { voiceAudioPlayer.reset() } catch (e: Exception) {}
        activeVoiceSegmentId = null
        voiceCurrentPos = -1L
    }

    fun playVoiceSegment(
        segment: ShadowRecording,
        startAtMs: Long = 0L,
        onPlaybackEnded: ((Boolean) -> Unit)? = null
    ) {
        try {
            voiceAudioPlayer.apply {
                reset()
                setDataSource(segment.filePath)
                setOnPreparedListener {
                    activeVoiceSegmentId = segment.id
                    val seekToMs = startAtMs.coerceAtLeast(0L).coerceAtMost((segment.endTime - segment.startTime).coerceAtLeast(0L))
                    if (seekToMs > 0L) seekTo(seekToMs.toInt())
                    start()
                }
                setOnCompletionListener {
                    activeVoiceSegmentId = null
                    voiceCurrentPos = -1L
                    onPlaybackEnded?.invoke(true)
                }
                setOnErrorListener { _, _, _ ->
                    activeVoiceSegmentId = null
                    voiceCurrentPos = -1L
                    onPlaybackEnded?.invoke(false)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            activeVoiceSegmentId = null
            Toast.makeText(context, context.getString(R.string.player_error_playing_audio), Toast.LENGTH_SHORT).show()
            onPlaybackEnded?.invoke(false)
        }
    }

    suspend fun playVoiceSegmentUntilComplete(segment: ShadowRecording): Boolean =
        suspendCancellableCoroutine { continuation ->
            var didResume = false
            fun finish(success: Boolean) {
                if (!didResume) {
                    didResume = true
                    if (continuation.isActive) continuation.resume(success)
                }
            }

            continuation.invokeOnCancellation { stopVoicePlayback() }

            playVoiceSegment(segment, onPlaybackEnded = ::finish)
        }

    fun pauseSourceForRepeatAttemptPlayback() {
        try { vlcPlayer.pause() } catch (e: Exception) {}
        isPlaying = false
        currentPos = vlcPlayer.time.coerceAtLeast(0L)
    }

    fun toggleVoiceSegment(segment: ShadowRecording) {
        if (activeVoiceSegmentId == segment.id) {
            if (voiceAudioPlayer.isPlaying) {
                voiceAudioPlayer.pause()
            } else {
                voiceAudioPlayer.start()
            }
        } else {
            playVoiceSegment(segment)
        }
    }

    fun seekVoiceSegment(segment: ShadowRecording, positionMs: Long) {
        if (activeVoiceSegmentId == segment.id) {
            try {
                voiceAudioPlayer.seekTo(positionMs.coerceAtLeast(0L).toInt())
                voiceCurrentPos = positionMs
            } catch (e: Exception) {
                playVoiceSegment(segment, positionMs)
            }
        } else {
            playVoiceSegment(segment, positionMs)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}
            try { vlcPlayer.release() } catch (e: Exception) {}
            try { voiceAudioPlayer.release() } catch (e: Exception) {}
            try { shadowAudioRecorder?.release() } catch (e: Exception) {}
            try { mediaSession.release() } catch (e: Exception) {}
            temporaryRepeatAttemptPaths.toList().forEach { deleteTemporaryRepeatAttempt(it) }
        }
    }

    BackHandler {
        onBack(playbackStorageItem())
    }

    LaunchedEffect(activeOriginalSegment) {
        activeOriginalSegment?.let { segment ->
            try {
                seekMainPlayer(segment.startTime, resumeAfterSeek = true)

                var seekWaitCount = 0
                while (Math.abs(vlcPlayer.time - segment.startTime) > 1500 && seekWaitCount < 40) {
                    delay(50)
                    seekWaitCount++
                }

                while (vlcPlayer.time < segment.endTime) { delay(50) }
            } finally {
                vlcPlayer.pause()
                if (activeOriginalSegment?.id == segment.id) activeOriginalSegment = null
            }
        }
    }

    LaunchedEffect(libraryItem.id, libraryItem.subtitleUri, libraryItem.sourceUrl) {
        val sourceUrl = libraryItem.sourceUrl
        if (
            !autoSubtitleRetryAttempted &&
            libraryItem.subtitleUri == null &&
            sourceUrl != null &&
            isYoutubeUrl(sourceUrl) &&
            prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true)
        ) {
            autoSubtitleRetryAttempted = true
            val preferredLanguage = prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE)
                ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE
            val setupData = withContext(Dispatchers.IO) {
                runCatching { fetchYoutubeSetupData(context, sourceUrl) }.getOrNull()
            }
            val subtitleUri = if (setupData != null) {
                youtubeSubtitleChoices = setupData.subtitleChoices
                youtubeSubtitleChoicesLoaded = true
                withContext(Dispatchers.IO) {
                    downloadPreferredYoutubeSubtitle(context, sourceUrl, setupData.subtitleChoices, preferredLanguage)
                }
            } else {
                null
            }
            if (subtitleUri != null) {
                val updatedItem = libraryItem.copy(subtitleUri = subtitleUri)
                libraryItem = updatedItem
                isSubtitlesVisible = true
                embeddedSubtitlesEnabled = true
                selectedYoutubeSubtitleKey = null
                LibraryManager(context).saveItem(updatedItem)
            }
        }
    }

    LaunchedEffect(libraryItem.subtitleUri) {
        if (libraryItem.subtitleUri != null) {
            isParsingSubtitles = true
            withContext(Dispatchers.IO) {
                parsedAudioCues = parseSimpleSubtitles(context, libraryItem.subtitleUri!!.toUri())
            }
            isParsingSubtitles = false
        } else {
            parsedAudioCues = emptyList()
            isParsingSubtitles = false
        }
    }

    LaunchedEffect(currentPos, parsedAudioCues, isSubtitlesVisible, subtitleDelayMs, isParsingSubtitles) {
        if (isSubtitlesVisible) {
            if (isParsingSubtitles) {
                currentSubtitleText = context.getString(R.string.player_loading_subtitles)
            } else if (parsedAudioCues.isNotEmpty()) {
                val effectivePos = currentPos - subtitleDelayMs
                val nextSubtitleText = findSubtitleCue(parsedAudioCues, effectivePos)?.text ?: ""
                if (currentSubtitleText != nextSubtitleText) currentSubtitleText = nextSubtitleText
            } else if (libraryItem.subtitleUri != null) {
                val noValidSubtitleText = context.getString(R.string.player_no_valid_subtitles)
                if (currentSubtitleText != noValidSubtitleText) {
                    currentSubtitleText = noValidSubtitleText
                }
            }
        } else {
            if (currentSubtitleText.isNotEmpty()) currentSubtitleText = ""
        }
    }

    DisposableEffect(actualMediaUri, videoLayout) {
        if (actualMediaUri == null) return@DisposableEffect onDispose { }
        if (libraryItem.isVideo && videoLayout == null) return@DisposableEffect onDispose { }

        var pfd: ParcelFileDescriptor? = null

        try {
            val mediaUri = actualMediaUri!!.toUri()
            val media = if (mediaUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(mediaUri, "r")
                if (pfd != null) {
                    VLCMedia(libVlc, pfd.fileDescriptor)
                } else {
                    VLCMedia(libVlc, mediaUri)
                }
            } else {
                VLCMedia(libVlc, mediaUri)
            }

            media.addOption(":network-caching=800")
            media.addOption(":file-caching=300")
            media.addOption(":http-reconnect")
            libraryItem.httpUserAgent?.takeIf { it.isNotBlank() }?.let {
                media.addOption(":http-user-agent=$it")
            }
            libraryItem.httpReferer?.takeIf { it.isNotBlank() }?.let {
                media.addOption(":http-referrer=$it")
            }
            vlcPlayer.media = media
            media.release()

            var initialSeekDone = false

            val listener = VLCMediaPlayer.EventListener { event ->
                when (event.type) {
                    VLCMediaPlayer.Event.Playing -> {
                        isPlaying = true
                        hasReachedEnd = false
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                if (!initialSeekDone && libraryItem.progress > 0) {
                                    vlcPlayer.time = libraryItem.progress
                                    initialSeekDone = true
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    VLCMediaPlayer.Event.Paused, VLCMediaPlayer.Event.Stopped -> isPlaying = false
                    VLCMediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        hasReachedEnd = true
                        if (duration > 0L) currentPos = duration
                    }
                    VLCMediaPlayer.Event.TimeChanged -> {
                        currentPos = event.timeChanged.coerceAtLeast(0)
                    }
                    VLCMediaPlayer.Event.LengthChanged -> {
                        if (event.lengthChanged > 0) duration = event.lengthChanged
                    }
                    VLCMediaPlayer.Event.EncounteredError -> {
                        isPlaying = false
                        val sourceUrl = libraryItem.sourceUrl
                        if (sourceUrl != null && !isRefreshingStream && streamRefreshAttempts < 1) {
                            uiScope.launch {
                                streamRefreshAttempts += 1
                                isRefreshingStream = true
                                val refreshedUri = withContext(Dispatchers.IO) {
                                    try {
                                        invalidateResolvedStreamUrl(context, sourceUrl, libraryItem.formatId)
                                        resolveYoutubeStreamUrl(context, sourceUrl, libraryItem.formatId)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }
                                }
                                if (!refreshedUri.isNullOrBlank()) {
                                    libraryItem = libraryItem.copy(progress = currentPos)
                                    LibraryManager(context).saveItem(libraryItem)
                                    actualMediaUri = refreshedUri
                                } else {
                                    Toast.makeText(context, context.getString(R.string.player_playback_failed_stream), Toast.LENGTH_SHORT).show()
                                }
                                isRefreshingStream = false
                            }
                        } else {
                            uiScope.launch {
                                Toast.makeText(context, context.getString(R.string.player_playback_failed_stream), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            if (libraryItem.isVideo && videoLayout != null) {
                vlcPlayer.detachViews()
                vlcPlayer.attachViews(videoLayout!!, null, false, false)
            }

            vlcPlayer.setEventListener(listener)
            vlcPlayer.play()

        } catch (e: Exception) {
            e.printStackTrace()
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, context.getString(R.string.player_initialization_error, e.localizedMessage.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }

        onDispose {
            val decision = decidePlaybackStorageItem(
                item = libraryItem,
                progress = vlcPlayer.time.coerceAtLeast(0),
                duration = if (vlcPlayer.length > 0) vlcPlayer.length else duration,
                recordings = recordings.toList(),
                actualMediaUri = actualMediaUri
            )
            if (decision.blockedRecordingMediaUri) {
                CrashReporter.recordHandled(
                    context,
                    "Recording persistence guard",
                    IllegalStateException("Blocked recording media URI from replacing source media URI")
                )
            }
            LibraryManager(context).saveItem(decision.item)

            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
        }
    }

    var tempFilePath by remember { mutableStateOf("") }

    fun startRecordingToFile(file: File): Boolean {
        val recorder = ShadowAudioRecorder(dictionaryEngine.isNoiseCancellationEnabled())
        return if (recorder.start(file)) {
            shadowAudioRecorder = recorder
            isRecording = true
            true
        } else {
            recorder.release()
            false
        }
    }

    fun stopRecordingSafe(
        currentFilePath: String,
        startTimeOverride: Long? = null,
        endTimeOverride: Long? = null,
        pausePlayer: Boolean = true,
        showShortToast: Boolean = true,
        saveRecording: Boolean = true
    ): ShadowRecording? {
        val recorder = shadowAudioRecorder ?: return null
        val endTime = endTimeOverride ?: vlcPlayer.time
        var success = true
        try {
            success = recorder.stop()
        } catch (e: Exception) {
            success = false
            if (showShortToast) Toast.makeText(context, context.getString(R.string.player_recording_too_short_toast), Toast.LENGTH_SHORT).show()
        } finally {
            shadowAudioRecorder = null
            isRecording = false
            if (pausePlayer) vlcPlayer.pause()
        }

        val startTime = startTimeOverride ?: recordStartTime
        if (success && endTime > startTime + MIN_SHADOW_SEGMENT_MS) {
            val recording = ShadowRecording(filePath = currentFilePath, startTime = startTime, endTime = endTime)
            if (saveRecording) {
                recordings.add(0, recording)
                syncWithStorage()
            }
            return recording
        } else {
            if (success && showShortToast) Toast.makeText(context, context.getString(R.string.player_recording_segment_too_short_toast), Toast.LENGTH_SHORT).show()
            try { File(currentFilePath).delete() } catch (e: Exception) {}
        }
        return null
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) Toast.makeText(context, context.getString(R.string.player_mic_required_toast), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(repeatPracticeSegment) {
        val segment = repeatPracticeSegment ?: return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            repeatPracticeSegment = null
            return@LaunchedEffect
        }
        if (segment.endTime <= segment.startTime + MIN_SHADOW_SEGMENT_MS) {
            Toast.makeText(context, context.getString(R.string.player_segment_too_short_repeat_toast), Toast.LENGTH_SHORT).show()
            repeatPracticeSegment = null
            return@LaunchedEffect
        }

        repeatAttemptCount = 0
        repeatPracticePhase = RepeatPracticePhase.Idle
        activeOriginalSegment = null
        stopVoicePlayback()
        val sessionTemporaryAttemptPaths = mutableSetOf<String>()

        fun trackTemporaryAttempt(path: String) {
            temporaryRepeatAttemptPaths.add(path)
            sessionTemporaryAttemptPaths.add(path)
        }

        fun deleteSessionTemporaryAttempt(path: String) {
            sessionTemporaryAttemptPaths.remove(path)
            deleteTemporaryRepeatAttempt(path)
        }

        try {
            while (repeatPracticeSegment?.id == segment.id) {
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Shadowing_${System.currentTimeMillis()}.m4a")
                tempFilePath = file.absolutePath
                recordStartTime = segment.startTime
                repeatPracticePhase = RepeatPracticePhase.RecordingAttempt

                vlcPlayer.time = segment.startTime
                if (!startRecordingToFile(file)) {
                    Toast.makeText(context, context.getString(R.string.player_recording_start_failed_toast), Toast.LENGTH_SHORT).show()
                    repeatPracticeSegment = null
                    break
                }

                vlcPlayer.play()

                var seekWaitCount = 0
                while (
                    kotlin.math.abs(vlcPlayer.time - segment.startTime) > 1500 &&
                    seekWaitCount < 40 &&
                    repeatPracticeSegment?.id == segment.id
                ) {
                    delay(50)
                    seekWaitCount++
                }

                while (vlcPlayer.time < segment.endTime && repeatPracticeSegment?.id == segment.id) {
                    delay(50)
                }

                if (repeatPracticeSegment?.id != segment.id) break

                val shouldSaveAttempt = latestSaveRepeatRecordings.value
                val attempt = stopRecordingSafe(
                    currentFilePath = file.absolutePath,
                    startTimeOverride = segment.startTime,
                    endTimeOverride = segment.endTime,
                    pausePlayer = true,
                    showShortToast = false,
                    saveRecording = shouldSaveAttempt
                )
                if (attempt != null) {
                    repeatAttemptCount += 1
                    if (!shouldSaveAttempt) trackTemporaryAttempt(attempt.filePath)

                    if (repeatPracticeSegment?.id != segment.id) break

                    repeatPracticePhase = RepeatPracticePhase.PlayingAttempt
                    pauseSourceForRepeatAttemptPlayback()
                    playVoiceSegmentUntilComplete(attempt)
                    if (!shouldSaveAttempt) deleteSessionTemporaryAttempt(attempt.filePath)
                }
            }
        } finally {
            if (shadowAudioRecorder != null && tempFilePath.isNotBlank()) {
                val shouldSavePartialAttempt = latestSaveRepeatRecordings.value
                val partialAttempt = stopRecordingSafe(
                    currentFilePath = tempFilePath,
                    startTimeOverride = segment.startTime,
                    endTimeOverride = vlcPlayer.time.coerceAtLeast(segment.startTime),
                    pausePlayer = true,
                    showShortToast = false,
                    saveRecording = shouldSavePartialAttempt
                )
                if (partialAttempt != null && !shouldSavePartialAttempt) deleteSessionTemporaryAttempt(partialAttempt.filePath)
            }
            try { vlcPlayer.pause() } catch (e: Exception) {}
            if (repeatPracticePhase == RepeatPracticePhase.PlayingAttempt) {
                stopVoicePlayback()
            }
            sessionTemporaryAttemptPaths.toList().forEach { deleteTemporaryRepeatAttempt(it) }
            if (repeatPracticeSegment == null || repeatPracticeSegment?.id == segment.id) {
                repeatPracticePhase = RepeatPracticePhase.Idle
            }
            tempFilePath = ""
        }
    }

    LaunchedEffect(Unit) {
        var lastFrameMs = 0L
        var lastPolledMainPos = currentPos
        while (true) {
            val activeTimeline = isPlaying ||
                voiceAudioPlayer.isPlaying ||
                activeOriginalSegment != null ||
                repeatPracticeSegment != null
            if (activeTimeline) {
                val frameMs = withFrameMillis { it }
                val shouldUpdateSourceTimeline = isPlaying ||
                    activeOriginalSegment != null ||
                    (repeatPracticeSegment != null && repeatPracticePhase != RepeatPracticePhase.PlayingAttempt)
                if (shouldUpdateSourceTimeline) {
                    val polledPos = vlcPlayer.time.coerceAtLeast(0L)
                    val frameDelta = if (lastFrameMs > 0L) (frameMs - lastFrameMs).coerceIn(0L, 100L) else 0L
                    currentPos = if (polledPos == lastPolledMainPos && frameDelta > 0L && duration > 0L) {
                        (currentPos + frameDelta).coerceAtMost(duration)
                    } else {
                        polledPos
                    }
                    lastPolledMainPos = polledPos
                }

                if (activeVoiceSegmentId != null) {
                    voiceCurrentPos = voiceAudioPlayer.currentPosition.toLong()
                } else {
                    voiceCurrentPos = -1L
                }
                lastFrameMs = frameMs
            } else {
                lastFrameMs = 0L
                lastPolledMainPos = currentPos
                delay(250)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                onBack(playbackStorageItem())
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(libraryItem.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                libraryItem.metadataSummary()?.let { summary ->
                    Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (libraryItem.subtitleUri != null || libraryItem.isVideo) {
                var showSubMenu by remember { mutableStateOf(false) }
                var spuTracks by remember { mutableStateOf<Array<org.videolan.libvlc.MediaPlayer.TrackDescription>>(emptyArray()) }

                val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        libraryItem = libraryItem.copy(subtitleUri = uri.toString())
                        isSubtitlesVisible = true
                        embeddedSubtitlesEnabled = true
                    }
                }

                Box {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = stringResource(R.string.player_toggle_subtitles),
                        tint = if (isSubtitlesVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { showSubMenu = true },
                                onLongClick = { showSubMenu = true }
                            )
                            .padding(8.dp)
                    )

                    DropdownMenu(
                        expanded = showSubMenu,
                        onDismissRequest = { showSubMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (isSubtitlesVisible) R.string.player_hide_captions else R.string.player_show_captions)) },
                            onClick = {
                                isSubtitlesVisible = !isSubtitlesVisible
                                showSubMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_add_custom_subtitles)) },
                            onClick = {
                                showSubMenu = false
                                subtitleLauncher.launch(arrayOf("*/*"))
                            }
                        )

                        LaunchedEffect(showSubMenu) {
                            if (showSubMenu) {
                                spuTracks = vlcPlayer.spuTracks ?: emptyArray()
                                syncEmbeddedSubtitleState(spuTracks)
                            }
                        }

                        LaunchedEffect(showSubMenu, libraryItem.sourceUrl) {
                            val sourceUrl = libraryItem.sourceUrl?.takeIf { isYoutubeUrl(it) }
                            if (showSubMenu && sourceUrl != null && !youtubeSubtitleChoicesLoaded && !isLoadingYoutubeSubtitleChoices) {
                                isLoadingYoutubeSubtitleChoices = true
                                youtubeSubtitleChoices = withContext(Dispatchers.IO) {
                                    runCatching { fetchYoutubeSetupData(context, sourceUrl).subtitleChoices }.getOrDefault(emptyList())
                                }
                                youtubeSubtitleChoicesLoaded = true
                                isLoadingYoutubeSubtitleChoices = false
                            }
                        }

                        val youtubeSourceUrl = libraryItem.sourceUrl?.takeIf { isYoutubeUrl(it) }
                        if (youtubeSourceUrl != null) {
                            if (isLoadingYoutubeSubtitleChoices) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_loading_youtube_captions)) },
                                    enabled = false,
                                    onClick = {}
                                )
                            } else if (youtubeSubtitleChoices.isEmpty() && youtubeSubtitleChoicesLoaded) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_no_youtube_captions)) },
                                    enabled = false,
                                    onClick = {}
                                )
                            } else {
                                youtubeSubtitleChoices.forEach { choice ->
                                    val choiceKey = "${choice.languageCode}:${choice.isAutoGenerated}"
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (selectedYoutubeSubtitleKey == choiceKey) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(choice.label)
                                            }
                                        },
                                        onClick = {
                                            showSubMenu = false
                                            selectedYoutubeSubtitleKey = choiceKey
                                            uiScope.launch {
                                                isParsingSubtitles = true
                                                val subtitleUri = withContext(Dispatchers.IO) {
                                                    downloadYoutubeSubtitle(context, youtubeSourceUrl, choice.languageCode, choice.isAutoGenerated)
                                                }
                                                if (subtitleUri != null) {
                                                    libraryItem = libraryItem.copy(subtitleUri = subtitleUri)
                                                    isSubtitlesVisible = true
                                                    embeddedSubtitlesEnabled = true
                                                    LibraryManager(context).saveItem(libraryItem)
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.player_subtitle_download_failed_toast), Toast.LENGTH_SHORT).show()
                                                    isParsingSubtitles = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            HorizontalDivider()
                        }

                        val visibleTracks = spuTracks.filter { it.id != -1 }
                        if (visibleTracks.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_no_embedded_captions)) },
                                enabled = false,
                                onClick = {}
                            )
                        }

                        visibleTracks.forEach { track ->
                            if (track.id != -1) {
                                DropdownMenuItem(
                                    text = { Text(track.name) },
                                    onClick = {
                                        vlcPlayer.spuTrack = track.id
                                        isSubtitlesVisible = true
                                        embeddedSubtitlesEnabled = true
                                        selectedEmbeddedSubtitleTrackId = track.id
                                        showSubMenu = false
                                    }
                                )
                            }
                        }

                        DropdownMenuItem(
                            text = { Text(stringResource(if (embeddedSubtitlesEnabled) R.string.player_disable_embedded_subs else R.string.player_enable_embedded_subs)) },
                            onClick = {
                                setEmbeddedSubtitlesEnabled(!embeddedSubtitlesEnabled, spuTracks)
                                showSubMenu = false
                            }
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(0.45f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                if (isRefreshingStream) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.player_resolving_live_stream), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                } else if (libraryItem.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            VLCVideoLayout(ctx)
                        },
                        update = { view ->
                            if (videoLayout !== view) videoLayout = view
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    if (albumArt != null) {
                        Image(
                            bitmap = albumArt, contentDescription = stringResource(R.string.player_album_art), contentScale = if (isSubtitlesVisible) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().then(if (isSubtitlesVisible) Modifier.blur(radius = 24.dp) else Modifier)
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E24)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(80.dp))
                        }
                    }
                }

                if (isSubtitlesVisible && currentSubtitleText.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                    JapaneseClickableSubtitle(
                        text = currentSubtitleText,
                        targetLanguage = dictionaryEngine.getTargetLanguage(),
                        onWordClicked = { clickedChunk ->
                            openDictionaryLookup(clickedChunk)
                        }
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(0.55f).fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(horizontal = 16.dp)) {

            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(currentPos), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Slider(
                            value = currentPos.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = {
                                seekMainPlayer(it.toLong(), resumeAfterSeek = isPlaying)
                            },
                            enabled = !isRecording && repeatPracticeSegment == null,
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }

                    if (!isRecording && repeatPracticeSegment == null) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                seekMainPlayer(currentPos - skipDurationMs, resumeAfterSeek = isPlaying)
                            }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FastRewind, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
                            Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (isRefreshingStream) Color.DarkGray else MaterialTheme.colorScheme.primary).clickable(enabled = !isRefreshingStream) {
                                if (isPlaying) {
                                    vlcPlayer.pause()
                                } else {
                                    playMainPlayer()
                                }
                            }, contentAlignment = Alignment.Center) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = {
                                seekMainPlayer(currentPos + skipDurationMs, resumeAfterSeek = isPlaying)
                            }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FastForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
                        }
                    }

                    if (isSubtitlesVisible) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.player_subtitle_delay), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    subtitleDelayMs = (subtitleDelayMs - 250L).coerceIn(-5000L, 5000L)
                                    prefs.edit { putLong(PREF_SUBTITLE_OFFSET_MS, subtitleDelayMs) }
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text(stringResource(R.string.player_minus_quarter_second), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                                Text(stringResource(R.string.settings_seconds_value, subtitleDelayMs / 1000f), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                TextButton(onClick = {
                                    subtitleDelayMs = (subtitleDelayMs + 250L).coerceIn(-5000L, 5000L)
                                    prefs.edit { putLong(PREF_SUBTITLE_OFFSET_MS, subtitleDelayMs) }
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text(stringResource(R.string.player_plus_quarter_second), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    repeatPracticeSegment?.let { segment ->
                        val repeatStatus = when (repeatPracticePhase) {
                            RepeatPracticePhase.RecordingAttempt -> stringResource(R.string.player_repeat_recording_with_source)
                            RepeatPracticePhase.PlayingAttempt -> stringResource(R.string.player_repeat_playing_attempt)
                            RepeatPracticePhase.Idle -> stringResource(R.string.player_repeat_starting)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.player_repeating_segment_status, formatTime(segment.startTime), formatTime(segment.endTime), repeatAttemptCount),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = {
                                        repeatPracticeSegment = null
                                        repeatPracticePhase = RepeatPracticePhase.Idle
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Text(stringResource(R.string.common_stop), color = Color(0xFFFF8A80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    repeatStatus,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.player_save_repeat_recordings),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Switch(
                                        checked = saveRepeatRecordings,
                                        onCheckedChange = {
                                            saveRepeatRecordings = it
                                            prefs.edit { putBoolean(PREF_SAVE_REPEAT_RECORDINGS, it) }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (repeatPracticeSegment != null) {
                                repeatPracticeSegment = null
                                repeatPracticePhase = RepeatPracticePhase.Idle
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                if (isRecording) {
                                    stopRecordingSafe(tempFilePath)
                                } else {
                                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Shadowing_${System.currentTimeMillis()}.m4a")
                                    tempFilePath = file.absolutePath
                                    recordStartTime = if (hasReachedEnd || (duration > 0L && currentPos >= duration - 500L)) 0L else vlcPlayer.time

                                    if (startRecordingToFile(file)) {
                                        playMainPlayer()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.player_recording_start_failed_toast), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording || repeatPracticeSegment != null) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRecording || repeatPracticeSegment != null) Color.White else MaterialTheme.colorScheme.onPrimary
                        ),
                        enabled = !isRefreshingStream
                    ) {
                        Icon(if (isRecording || repeatPracticeSegment != null) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                repeatPracticeSegment != null -> stringResource(R.string.player_stop_repeat_mode)
                                isRecording -> stringResource(R.string.player_stop_shadowing)
                                else -> stringResource(R.string.player_start_shadowing)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                if (recordings.isNotEmpty() && !isRecording && repeatPracticeSegment == null) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.player_latest_recording), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            if (recordings.size > 1) {
                                TextButton(onClick = { showBacklog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                                    Text(stringResource(R.string.player_view_backlog, recordings.size - 1), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                            }
                        }

                        val latest = recordings.first()
                        RecordingItemCard(
                            rec = latest,
                            context = context,
                            originalMediaUri = actualMediaUri ?: libraryItem.mediaUri,
                            onPlayOriginal = {
                                activeOriginalSegment = if (activeOriginalSegment?.id == latest.id) null else latest
                            },
                            onPlayVoice = {
                                toggleVoiceSegment(latest)
                            },
                            onSeekOriginal = { targetMs -> seekMainPlayer(targetMs, resumeAfterSeek = false) },
                            onSeekVoice = { targetMs -> seekVoiceSegment(latest, targetMs) },
                            onRepeatPractice = {
                                toggleRepeatPractice(latest)
                            },
                            onDelete = {
                                try { File(latest.filePath).delete() } catch (e: Exception) {}
                                if (repeatPracticeSegment?.id == latest.id) repeatPracticeSegment = null
                                if (activeVoiceSegmentId == latest.id) {
                                    try { voiceAudioPlayer.stop() } catch (e: Exception) {}
                                    activeVoiceSegmentId = null
                                }
                                recordings.remove(latest)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(context, File(latest.filePath)) },
                            isRepeatPracticeActive = repeatPracticeSegment?.id == latest.id,
                            currentOriginalTime = currentPos,
                            currentRecordedTime = if (activeVoiceSegmentId == latest.id) voiceCurrentPos else -1L,
                            isOriginalPlaying = activeOriginalSegment?.id == latest.id,
                            isRecordedPlaying = activeVoiceSegmentId == latest.id && voiceAudioPlayer.isPlaying
                        )
                    }
                }
            }
        }
    }

    if (showDictQuery != null) {
        HoshiDictionaryBottomSheet(
            query = showDictQuery!!,
            engine = dictionaryEngine,
            onDismiss = { dismissDictionaryLookup() }
        )
    }

    if (showBacklog) {
        ModalBottomSheet(
            onDismissRequest = { showBacklog = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.player_session_backlog), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recordings.drop(1)) { rec ->
                        RecordingItemCard(
                            rec = rec,
                            context = context,
                            originalMediaUri = actualMediaUri ?: libraryItem.mediaUri,
                            onPlayOriginal = {
                                activeOriginalSegment = if (activeOriginalSegment?.id == rec.id) null else rec
                            },
                            onPlayVoice = {
                                toggleVoiceSegment(rec)
                            },
                            onSeekOriginal = { targetMs -> seekMainPlayer(targetMs, resumeAfterSeek = false) },
                            onSeekVoice = { targetMs -> seekVoiceSegment(rec, targetMs) },
                            onRepeatPractice = {
                                toggleRepeatPractice(rec, collapseBacklogOnStart = true)
                            },
                            onDelete = {
                                try { File(rec.filePath).delete() } catch (e: Exception) {}
                                if (repeatPracticeSegment?.id == rec.id) repeatPracticeSegment = null
                                if (activeVoiceSegmentId == rec.id) {
                                    try { voiceAudioPlayer.stop() } catch (e: Exception) {}
                                    activeVoiceSegmentId = null
                                }
                                recordings.remove(rec)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(context, File(rec.filePath)) },
                            isRepeatPracticeActive = repeatPracticeSegment?.id == rec.id,
                            currentOriginalTime = currentPos,
                            currentRecordedTime = if (activeVoiceSegmentId == rec.id) voiceCurrentPos else -1L,
                            isOriginalPlaying = activeOriginalSegment?.id == rec.id,
                            isRecordedPlaying = activeVoiceSegmentId == rec.id && voiceAudioPlayer.isPlaying
                        )
                    }
                }
            }
        }
    }
}
