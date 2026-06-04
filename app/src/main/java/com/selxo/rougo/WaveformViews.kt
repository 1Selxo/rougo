package com.selxo.rougo
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Size
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.selxo.rougo.dictionary.DeinflectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.system.exitProcess

// --- YT-DLP IMPORTS ---
import com.yausername.ffmpeg.FFmpeg
import com.yausername.ffmpeg.execute
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

// --- IMPORT ALIASES ---
import android.media.MediaPlayer as AndroidMediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VLCMedia
import org.videolan.libvlc.MediaPlayer as VLCMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

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
                    if (isLoading) "Analyzing $label..." else "$label audio unavailable",
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
    downloadState: LibraryDownloadState = LibraryDownloadState.Idle
) {
    val context = LocalContext.current
    val progressPct = if (item.duration > 0) (item.progress.toFloat() / item.duration.toFloat()) else 0f
    val albumArt = loadAlbumArt(context, item.mediaUri, item.isVideo, item.coverArtPath, item.id, item.sourceUrl)
    val metadataLine = item.metadataSummary()
    val itemType = item.displaySourceLabel()
    var showDownloadMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(68.dp).height(92.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(bitmap = albumArt, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                        Text("${item.recordings.size} recordings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${(progressPct * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (item.subtitleUri != null) "Subtitles" else "No subtitles",
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
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                                }
                                LibraryDownloadState.Idle -> {
                                    Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showDownloadMenu,
                            onDismissRequest = { showDownloadMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete download") },
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
                    Text("Segment: ${formatTime(rec.startTime)} - ${formatTime(rec.endTime)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    if (currentRecordedTime >= 0L) {
                        Text("Recording: ${formatTime(currentRecordedTime)} / ${formatTime(segmentDuration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
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
                Text(if (isRepeatPracticeActive) "Stop Repeat" else "Repeat Segment", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
