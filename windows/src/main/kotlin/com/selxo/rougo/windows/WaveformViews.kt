package com.selxo.rougo.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import java.net.URL
import javax.imageio.ImageIO

private const val WAVEFORM_BUCKET_COUNT = 96
private const val WAVEFORM_CACHE_VERSION = 6
private const val MIN_SHADOW_SEGMENT_MS = 250L

@Composable
fun LibraryCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: (() -> Unit)?,
    downloadState: LibraryDownloadState,
    onDownload: (() -> Unit)?,
    onDeleteDownload: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val albumArt = loadAlbumArt(item.mediaUri, item.coverArtPath, item.sourceUrl)
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(albumArt, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(5.dp))
                Text(item.metadataSummary() ?: item.displaySourceLabel(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(item.displaySourceLabel(), fontSize = 11.sp) })
                    AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(14.dp)) }, label = { Text(if (item.subtitleUri != null) "Subtitles" else "No subtitles", fontSize = 11.sp) })
                    if (item.recordings.isNotEmpty()) {
                        AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp)) }, label = { Text("${item.recordings.size} recordings", fontSize = 11.sp) })
                    }
                }
                if (item.duration > 0L) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (item.progress.toFloat() / item.duration.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text("${formatPercent(item.progress, item.duration)}% - ${formatTime(item.progress)} / ${formatTime(item.duration)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (downloadState == LibraryDownloadState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            } else if (onDownload != null) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (onMove != null) {
                        DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = {
                            showMenu = false
                            onMove()
                        })
                    }
                    if (onDeleteDownload != null) {
                        DropdownMenuItem(text = { Text("Delete download") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                            showMenu = false
                            onDeleteDownload()
                        })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                        showMenu = false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
fun AudioWaveformComparison(
    originalAmplitudes: List<Float>,
    originalPitches: List<Float?>,
    recordedAmplitudes: List<Float>,
    recordedPitches: List<Float?>,
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
            label = "Original",
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
            label = "Recorded",
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
    pitches: List<Float?>,
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
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
            if (amplitudes.isEmpty() || amplitudes.all { it <= 0f }) {
                Text(
                    if (isLoading) "Analyzing $label..." else "$label audio unavailable",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                return@Box
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = size.width / amplitudes.size
                val midY = size.height / 2f

                val barWidth = (step * 0.7f).coerceAtLeast(2f)
                for (i in amplitudes.indices) {
                    val amp = amplitudes[i]
                    val x = i * step + step / 2f
                    val barHeight = (amp * size.height).coerceAtLeast(4f)

                    drawLine(
                        color = color.copy(alpha = 0.8f),
                        start = Offset(x, midY - barHeight / 2f),
                        end = Offset(x, midY + barHeight / 2f),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }

                if (pitches.isNotEmpty()) {
                    val pitchPath = Path()
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
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                if (progress > 0f) {
                    val cursorX = size.width * progress.coerceIn(0f, 1f)
                    drawLine(
                        color = cursorColor,
                        start = Offset(cursorX, 0f),
                        end = Offset(cursorX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingItemCard(
    rec: ShadowRecording,
    originalMediaUri: String,
    onPlayOriginal: () -> Unit,
    onPlayVoice: () -> Unit,
    onSeekOriginal: (Long) -> Unit,
    onSeekVoice: (Long) -> Unit = {},
    onRepeatPractice: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    isRepeatPracticeActive: Boolean,
    currentOriginalTime: Long = -1L,
    currentRecordedTime: Long = -1L,
    isOriginalPlaying: Boolean,
    isRecordedPlaying: Boolean
) {
    var waveform by remember(rec.id) { mutableStateOf<Pair<Pair<List<Float>, List<Float?>>, Pair<List<Float>, List<Float?>>>?>(null) }
    LaunchedEffect(rec, originalMediaUri) {
        waveform = withContext(Dispatchers.IO) {
            val originalKey = "v$WAVEFORM_CACHE_VERSION:original:${originalMediaUri}:${rec.startTime}:${rec.endTime}"
            val recordedFile = File(rec.filePath)
            val recordedKey = "v$WAVEFORM_CACHE_VERSION:recorded:${rec.filePath}:${recordedFile.lastModified()}:${recordedFile.length()}"
            val originalData = if (rec.endTime > rec.startTime + MIN_SHADOW_SEGMENT_MS) {
                extractAudioDataCached(originalKey, originalMediaUri, rec.startTime, rec.endTime, WAVEFORM_BUCKET_COUNT)
            } else {
                emptyList<Float>() to emptyList<Float?>()
            }
            originalData to extractAudioDataCached(recordedKey, rec.filePath, 0, 0, WAVEFORM_BUCKET_COUNT)
        }
    }
    val segmentDuration = (rec.endTime - rec.startTime).coerceAtLeast(1L)
    val originalProgress = if (currentOriginalTime in rec.startTime..rec.endTime) {
        (currentOriginalTime - rec.startTime).toFloat() / segmentDuration.toFloat()
    } else {
        0f
    }
    val recordedProgress = if (currentRecordedTime >= 0L) {
        currentRecordedTime.toFloat() / segmentDuration.toFloat()
    } else {
        0f
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Segment: ${formatTime(rec.startTime)} - ${formatTime(rec.endTime)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    if (currentRecordedTime >= 0L) {
                        Text(
                            "Position: ${formatTime(currentRecordedTime)} / ${formatTime(segmentDuration)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
                Row {
                    IconButton(onClick = onShare, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val current = waveform
            AudioWaveformComparison(
                originalAmplitudes = current?.first?.first.orEmpty(),
                originalPitches = current?.first?.second.orEmpty(),
                recordedAmplitudes = current?.second?.first.orEmpty(),
                recordedPitches = current?.second?.second.orEmpty(),
                onPlayOriginal = onPlayOriginal,
                onPlayVoice = onPlayVoice,
                onSeekOriginal = { fraction ->
                    onSeekOriginal(rec.startTime + (segmentDuration * fraction).toLong())
                },
                onSeekVoice = { fraction ->
                    onSeekVoice((segmentDuration * fraction).toLong())
                },
                isOriginalPlaying = isOriginalPlaying,
                isRecordedPlaying = isRecordedPlaying,
                originalProgress = originalProgress,
                recordedProgress = recordedProgress,
                isLoading = current == null
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRepeatPractice,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isRepeatPracticeActive) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (isRepeatPracticeActive) Icons.Default.Stop else Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isRepeatPracticeActive) "Stop Repeat" else "Repeat Segment", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun loadAlbumArt(mediaUri: String, cachedCoverPath: String?, sourceUrl: String?): androidx.compose.ui.graphics.ImageBitmap? {
    var image by remember(mediaUri, cachedCoverPath, sourceUrl) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(mediaUri, cachedCoverPath, sourceUrl) {
        image = withContext(Dispatchers.IO) {
            loadImageBitmap(cachedCoverPath)
                ?: sourceUrl?.let { youtubeThumbnailUrl(it) }?.let { loadImageBitmap(it) }
        }
    }
    return image
}

private fun loadImageBitmap(pathOrUrl: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (pathOrUrl.isNullOrBlank()) return null
    return runCatching {
        val bytes = if (pathOrUrl.startsWith("http", ignoreCase = true)) URL(pathOrUrl).readBytes() else File(pathOrUrl).takeIf { it.isFile }?.readBytes()
        bytes?.let { ImageIO.read(ByteArrayInputStream(it))?.toComposeImageBitmap() }
    }.getOrNull()
}
