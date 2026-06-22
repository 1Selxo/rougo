package com.selxo.rougo

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AudioWaveformComparison(
    originalAmplitudes: List<Float>,
    originalPitches: List<Float?>? = null,
    recordedAmplitudes: List<Float>,
    recordedPitches: List<Float?>? = null,
    onPlayOriginal: () -> Unit,
    onPlayVoice: () -> Unit,
    onSeekOriginal: (Float) -> Unit = {},
    onSeekVoice: (Float) -> Unit = {},
    isOriginalPlaying: Boolean = false,
    isRecordedPlaying: Boolean = false,
    originalProgress: Float = 0f,
    recordedProgress: Float = 0f,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val recordedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        WaveformTrack(
            amplitudes = originalAmplitudes,
            pitches = originalPitches,
            color = accent,
            pitchColor = MaterialTheme.colorScheme.secondary,
            cursorColor = accent,
            label = stringResource(R.string.waveform_original),
            onClick = onPlayOriginal,
            onSeek = onSeekOriginal,
            isPlaying = isOriginalPlaying,
            progress = originalProgress,
            isLoading = isLoading
        )
        Spacer(Modifier.height(8.dp))
        WaveformTrack(
            amplitudes = recordedAmplitudes,
            pitches = recordedPitches,
            color = recordedColor,
            pitchColor = MaterialTheme.colorScheme.secondary,
            cursorColor = accent,
            label = stringResource(R.string.waveform_recorded),
            onClick = onPlayVoice,
            onSeek = onSeekVoice,
            isPlaying = isRecordedPlaying,
            progress = recordedProgress,
            isLoading = isLoading
        )
    }
}
@Composable
fun WaveformTrack(
    amplitudes: List<Float>,
    pitches: List<Float?>? = null,
    color: Color,
    pitchColor: Color,
    cursorColor: Color,
    label: String,
    onClick: () -> Unit,
    onSeek: (Float) -> Unit = {},
    isPlaying: Boolean = false,
    progress: Float = 0f,
    isLoading: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = color
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(amplitudes) {
                    detectTapGestures { offset ->
                        if (amplitudes.isNotEmpty() && size.width > 0) {
                            onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (amplitudes.isEmpty()) {
                Text(
                    if (isLoading) stringResource(R.string.waveform_analyzing, label) else stringResource(R.string.waveform_audio_unavailable, label),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                return@Box
            }

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {

            val step = size.width / amplitudes.size
            val midY = size.height / 2

            // Draw Symmetrical Amplitudes Bars
            val barWidth = (step * 0.7f).coerceAtLeast(2f)
            for (i in amplitudes.indices) {
                val amp = amplitudes[i]
                val x = i * step + step / 2f
                val barHeight = (amp * size.height).coerceAtLeast(4f)

                drawLine(
                    color = color.copy(alpha = 0.8f),
                    start = androidx.compose.ui.geometry.Offset(x, midY - barHeight / 2f),
                    end = androidx.compose.ui.geometry.Offset(x, midY + barHeight / 2f),
                    strokeWidth = barWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Draw Smooth Pitch Contour
            if (pitches != null && pitches.isNotEmpty()) {
                val pitchPath = androidx.compose.ui.graphics.Path()
                var isFirst = true
                val pitchScale = pitchDisplayScale(pitches)

                pitches.forEachIndexed { index, pitchHz ->
                    if (pitchHz != null && pitchScale != null) {
                        val normalizedY = pitchToNormalizedY(pitchHz, pitchScale)
                        val y = normalizedY * size.height
                        val x = index * step + step / 2f

                        if (isFirst) {
                            pitchPath.moveTo(x, y)
                            isFirst = false
                        } else {
                            pitchPath.lineTo(x, y)
                        }
                    } else {
                        isFirst = true
                    }
                }
                drawPath(
                    path = pitchPath,
                    color = pitchColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }

            if (progress > 0f) {
                val cursorX = size.width * progress.coerceIn(0f, 1f)
                drawLine(
                    color = cursorColor,
                    start = androidx.compose.ui.geometry.Offset(cursorX, 0f),
                    end = androidx.compose.ui.geometry.Offset(cursorX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        }
    }
}
@Composable
fun LibraryCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    downloadState: LibraryDownloadState = LibraryDownloadState.Idle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val progressPct = if (item.duration > 0) (item.progress.toFloat() / item.duration.toFloat()) else 0f
    val albumArt = loadAlbumArt(context, item.mediaUri, item.isVideo, item.coverArtPath, item.id, item.sourceUrl)
    val metadataLine = item.metadataSummary()
    val itemType = item.displaySourceLabel(context)
    var showDownloadMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(68.dp).height(92.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(bitmap = albumArt, contentDescription = stringResource(R.string.artwork_cover), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        if (item.isVideo) Icons.Default.Movie else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (metadataLine != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(metadataLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(itemType, fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                if (item.hasDownloadedLocalCopy()) {
                                    Icons.Default.CheckCircle
                                } else if (item.sourceUrl != null) {
                                    Icons.Default.Cloud
                                } else if (item.isVideo) {
                                    Icons.Default.Movie
                                } else {
                                    Icons.Default.Audiotrack
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    if (item.recordings.isNotEmpty()) {
                        Text(stringResource(R.string.library_recordings_count, item.recordings.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.library_progress_percent, (progressPct * 100).toInt()), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(if (item.subtitleUri != null) R.string.library_has_subtitles else R.string.library_no_subtitles),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progressPct },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Column(
                modifier = Modifier.width(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDownload != null || onDeleteDownload != null || downloadState != LibraryDownloadState.Idle) {
                    Box {
                        IconButton(
                            onClick = {
                                if (downloadState == LibraryDownloadState.Complete && onDeleteDownload != null) {
                                    showDownloadMenu = true
                                } else {
                                    onDownload?.invoke()
                                }
                            },
                            enabled = downloadState != LibraryDownloadState.Loading &&
                                (onDownload != null || (downloadState == LibraryDownloadState.Complete && onDeleteDownload != null)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            when (downloadState) {
                                LibraryDownloadState.Loading -> {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                                LibraryDownloadState.Complete -> {
                                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.common_downloaded), tint = MaterialTheme.colorScheme.primary)
                                }
                                LibraryDownloadState.Idle -> {
                                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.common_download), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showDownloadMenu,
                            onDismissRequest = { showDownloadMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_delete_download)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                onClick = {
                                    showDownloadMenu = false
                                    onDeleteDownload?.invoke()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun RecordingItemCard(
    rec: ShadowRecording,
    context: Context,
    originalMediaUri: String,
    onPlayOriginal: () -> Unit,
    onPlayVoice: () -> Unit,
    onSeekOriginal: (Long) -> Unit = {},
    onSeekVoice: (Long) -> Unit = {},
    onRepeatPractice: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    isRepeatPracticeActive: Boolean = false,
    currentOriginalTime: Long = -1L,
    currentRecordedTime: Long = -1L,
    isOriginalPlaying: Boolean = false,
    isRecordedPlaying: Boolean = false
) {
    var originalAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var originalPitches by remember { mutableStateOf<List<Float?>>(emptyList()) }
    var recordedAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var recordedPitches by remember { mutableStateOf<List<Float?>>(emptyList()) }
    var isWaveformLoading by remember { mutableStateOf(true) }

    LaunchedEffect(rec, originalMediaUri) {
        isWaveformLoading = true
        val (originalData, recordedData) = withContext(Dispatchers.IO) {
            val originalKey = "v$WAVEFORM_CACHE_VERSION:original:${originalMediaUri}:${rec.startTime}:${rec.endTime}"
            val recordedFile = File(rec.filePath)
            val recordedKey = "v$WAVEFORM_CACHE_VERSION:recorded:${rec.filePath}:${recordedFile.lastModified()}:${recordedFile.length()}"
            val originalData = if (rec.endTime > rec.startTime + MIN_SHADOW_SEGMENT_MS) {
                extractAudioDataCached(context, originalKey, Uri.parse(originalMediaUri), rec.startTime, rec.endTime, WAVEFORM_BUCKET_COUNT)
            } else {
                Pair(emptyList<Float>(), emptyList<Float?>())
            }
            val recordedData = extractAudioDataCached(context, recordedKey, Uri.fromFile(recordedFile), 0, 0, WAVEFORM_BUCKET_COUNT)
            Pair(originalData, recordedData)
        }
        originalAmplitudes = originalData.first
        originalPitches = originalData.second
        recordedAmplitudes = recordedData.first
        recordedPitches = recordedData.second
        isWaveformLoading = false
    }

    val segmentDuration = (rec.endTime - rec.startTime).coerceAtLeast(1L)

    val originalProgress = if (currentOriginalTime in rec.startTime..rec.endTime) {
        (currentOriginalTime - rec.startTime).toFloat() / segmentDuration.toFloat()
    } else 0f

    val recordedProgress = if (currentRecordedTime >= 0) {
        currentRecordedTime.toFloat() / segmentDuration.toFloat()
    } else 0f

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recording_segment_range, formatTime(rec.startTime), formatTime(rec.endTime)), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    if (currentRecordedTime >= 0L) {
                        Text(stringResource(R.string.recording_position, formatTime(currentRecordedTime), formatTime(segmentDuration)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                Row {
                    IconButton(onClick = onShare, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.common_share), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AudioWaveformComparison(
                originalAmplitudes = originalAmplitudes,
                originalPitches = originalPitches,
                recordedAmplitudes = recordedAmplitudes,
                recordedPitches = recordedPitches,
                onPlayOriginal = onPlayOriginal,
                onPlayVoice = onPlayVoice,
                onSeekOriginal = { fraction ->
                    val target = rec.startTime + (segmentDuration * fraction).toLong()
                    onSeekOriginal(target)
                },
                onSeekVoice = { fraction ->
                    val target = (segmentDuration * fraction).toLong()
                    onSeekVoice(target)
                },
                isOriginalPlaying = isOriginalPlaying,
                isRecordedPlaying = isRecordedPlaying,
                originalProgress = originalProgress,
                recordedProgress = recordedProgress,
                isLoading = isWaveformLoading
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRepeatPractice,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isRepeatPracticeActive) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (isRepeatPracticeActive) Icons.Default.Stop else Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (isRepeatPracticeActive) R.string.recording_stop_repeat else R.string.recording_repeat_segment), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
