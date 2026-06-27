package com.selxo.rougo.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selxo.rougo.dictionary.DeinflectorRegistry
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToDictionaries: () -> Unit,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    accentColor: String,
    onAccentColorChanged: (String) -> Unit,
    systemDark: Boolean
) {
    val engine = remember { DictionaryEngine.instance }
    val prefs = Prefs.app
    val usesDarkSurfaces = when (themeMode) {
        THEME_LIGHT -> false
        THEME_SYSTEM -> systemDark
        else -> true
    }
    var noiseCancelEnabled by remember { mutableStateOf(engine.isNoiseCancellationEnabled()) }
    var dictionaryTargetLanguage by remember { mutableStateOf(engine.getTargetLanguage()) }
    var preferredYoutubeResolution by remember {
        mutableStateOf(prefs.getString(PREF_YOUTUBE_RESOLUTION, DEFAULT_YOUTUBE_RESOLUTION) ?: DEFAULT_YOUTUBE_RESOLUTION)
    }
    var autoYoutubeSubtitles by remember {
        mutableStateOf(prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true))
    }
    var preferredYoutubeSubtitleLanguage by remember {
        mutableStateOf(prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE) ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE)
    }
    var skipSeconds by remember {
        mutableIntStateOf(prefs.getInt(PREF_SKIP_SECONDS, DEFAULT_SKIP_SECONDS).coerceIn(1, 30))
    }
    var subtitleOffsetSteps by remember {
        mutableIntStateOf((prefs.getLong(PREF_SUBTITLE_OFFSET_MS, DEFAULT_SUBTITLE_OFFSET_MS) / 250L).toInt().coerceIn(-20, 20))
    }
    var showDictionaryLanguageMenu by remember { mutableStateOf(false) }
    var showYoutubeQualityMenu by remember { mutableStateOf(false) }
    var showYoutubeSubtitleLanguageMenu by remember { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var manualUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }
    val settingsScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Theme", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Choose dark, black, light, or system", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Box {
                        TextButton(onClick = { showThemeMenu = true }) {
                            Text(THEME_MODE_OPTIONS.firstOrNull { it.key == themeMode }?.label ?: "Dark")
                        }
                        DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                            THEME_MODE_OPTIONS.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    onThemeModeChanged(option.key)
                                    showThemeMenu = false
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Accent", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ACCENT_OPTIONS) { option ->
                        val selected = accentColor == option.key
                        val swatch = if (usesDarkSurfaces) option.darkColor else option.lightColor
                        Box(
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(swatch).clickable { onAccentColorChanged(option.key) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) Icon(Icons.Default.Check, contentDescription = option.label, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth().clickable { onNavigateToDictionaries() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dictionaries", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Manage and import Yomitan dictionaries", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Target Language", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Used for dictionary lookups", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Box {
                        TextButton(onClick = { showDictionaryLanguageMenu = true }) {
                            Text(DeinflectorRegistry.languageOptions.firstOrNull { it.code == dictionaryTargetLanguage }?.label ?: "Japanese")
                        }
                        DropdownMenu(expanded = showDictionaryLanguageMenu, onDismissRequest = { showDictionaryLanguageMenu = false }) {
                            DeinflectorRegistry.languageOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    dictionaryTargetLanguage = option.code
                                    engine.setTargetLanguage(option.code)
                                    showDictionaryLanguageMenu = false
                                })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Noise Cancellation", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Reduce background noise during shadowing", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = noiseCancelEnabled, onCheckedChange = {
                        noiseCancelEnabled = it
                        engine.setNoiseCancellationEnabled(it)
                    })
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Player Controls", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Customize seek buttons and subtitle timing", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Skip Buttons", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                    Slider(value = skipSeconds.toFloat(), onValueChange = {
                        val next = it.roundToInt().coerceIn(1, 30)
                        skipSeconds = next
                        prefs.putInt(PREF_SKIP_SECONDS, next)
                    }, valueRange = 1f..30f, steps = 28, modifier = Modifier.weight(1f))
                    Text("${skipSeconds}s", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val subtitleOffsetMs = subtitleOffsetSteps * 250L
                    Text("Subtitle Offset", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                    Slider(value = subtitleOffsetSteps.toFloat(), onValueChange = {
                        val next = it.roundToInt().coerceIn(-20, 20)
                        subtitleOffsetSteps = next
                        prefs.putLong(PREF_SUBTITLE_OFFSET_MS, next * 250L)
                    }, valueRange = -20f..20f, steps = 39, modifier = Modifier.weight(1f))
                    Text("%.2fs".format(subtitleOffsetMs / 1000f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HighQuality, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("YouTube Quality", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Skip quality picker when sharing videos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Box {
                        TextButton(onClick = { showYoutubeQualityMenu = true }) {
                            Text(YOUTUBE_RESOLUTION_OPTIONS.firstOrNull { it.key == preferredYoutubeResolution }?.label ?: "720p")
                        }
                        DropdownMenu(expanded = showYoutubeQualityMenu, onDismissRequest = { showYoutubeQualityMenu = false }) {
                            YOUTUBE_RESOLUTION_OPTIONS.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    preferredYoutubeResolution = option.key
                                    prefs.putString(PREF_YOUTUBE_RESOLUTION, option.key)
                                    showYoutubeQualityMenu = false
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Auto YouTube Subtitles", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        Text("Use your preferred captions when quality picker is skipped", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = autoYoutubeSubtitles, onCheckedChange = {
                        autoYoutubeSubtitles = it
                        prefs.putBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, it)
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Subtitle Language", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        Text("Default is Japanese when available", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Box {
                        TextButton(onClick = { showYoutubeSubtitleLanguageMenu = true }, enabled = autoYoutubeSubtitles) {
                            Text(YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS.firstOrNull { it.key == preferredYoutubeSubtitleLanguage }?.label ?: preferredYoutubeSubtitleLanguage)
                        }
                        DropdownMenu(expanded = showYoutubeSubtitleLanguageMenu, onDismissRequest = { showYoutubeSubtitleLanguageMenu = false }) {
                            YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    preferredYoutubeSubtitleLanguage = option.key
                                    prefs.putString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, option.key)
                                    showYoutubeSubtitleLanguageMenu = false
                                })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Version", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(updateStatus.ifBlank { "$APP_VERSION (Windows)" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    TextButton(enabled = !isCheckingUpdate, onClick = {
                        isCheckingUpdate = true
                        updateStatus = "Checking..."
                        settingsScope.launch {
                            val info = checkForUpdates()
                            if (info != null && isUpdateAvailable(info, APP_VERSION)) {
                                manualUpdateInfo = info
                                updateStatus = "$APP_VERSION (Windows)"
                            } else {
                                updateStatus = "Rougo is up to date."
                            }
                            isCheckingUpdate = false
                        }
                    }) {
                        if (isCheckingUpdate) LinearProgressIndicator(Modifier.width(42.dp)) else Text("Check")
                    }
                }
            }
        }
    }

    manualUpdateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { manualUpdateInfo = null },
            title = { Text("Update Available (${info.tagName})", fontWeight = FontWeight.Bold) },
            text = { Text(info.body.ifBlank { "A new version of Rougo is available." }, fontSize = 12.sp) },
            confirmButton = {
                Button(onClick = {
                    manualUpdateInfo = null
                    openUpdateDownload(info)
                }) { Text("Update Now") }
            },
            dismissButton = {
                TextButton(onClick = { manualUpdateInfo = null }) { Text("Later") }
            }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySettingsScreen(onBack: () -> Unit) {
    val engine = remember { DictionaryEngine.instance }
    val scope = rememberCoroutineScope()
    var installedDicts by remember { mutableStateOf(engine.getInstalledDictionaries()) }
    var dictOrder by remember { mutableStateOf(engine.getDictOrder()) }
    var importStatus by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var blockCollapseByDict by remember(installedDicts) {
        mutableStateOf(installedDicts.associateWith { engine.isDictionaryBlockCollapseEnabled(it) })
    }
    val sortedDicts = remember(installedDicts, dictOrder) {
        installedDicts.sortedBy { name -> dictOrder.indexOf(name).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }

    fun refresh() {
        installedDicts = engine.getInstalledDictionaries()
        dictOrder = engine.getDictOrder()
        blockCollapseByDict = installedDicts.associateWith { engine.isDictionaryBlockCollapseEnabled(it) }
    }

    fun moveDict(name: String, up: Boolean) {
        val currentOrder = sortedDicts.toMutableList()
        val index = currentOrder.indexOf(name)
        if (up && index > 0) {
            currentOrder[index] = currentOrder[index - 1].also { currentOrder[index - 1] = name }
        } else if (!up && index < currentOrder.size - 1) {
            currentOrder[index] = currentOrder[index + 1].also { currentOrder[index + 1] = name }
        }
        dictOrder = currentOrder
        engine.saveDictOrder(currentOrder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Dictionaries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val zip = openZipFileDialog()
                        if (zip != null) {
                            isImporting = true
                            scope.launch {
                                engine.importZip(zip) { status ->
                                    importStatus = status
                                    if (status.isEmpty()) {
                                        isImporting = false
                                        refresh()
                                    }
                                }
                            }
                        }
                    }, enabled = !isImporting) {
                        Icon(Icons.Default.Add, contentDescription = "Import", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (isImporting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(importStatus, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
            }

            if (installedDicts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No dictionaries installed.\nTap + to import a Yomitan ZIP.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sortedDicts) { dictName ->
                        val blockCollapseEnabled = blockCollapseByDict[dictName] ?: true
                        val isPitchDictionary = engine.isPitchDictionary(dictName)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Book, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Text(dictName, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { moveDict(dictName, true) }, enabled = sortedDicts.indexOf(dictName) > 0) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                                    }
                                    IconButton(onClick = { moveDict(dictName, false) }, enabled = sortedDicts.indexOf(dictName) < sortedDicts.size - 1) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                                    }
                                    IconButton(onClick = {
                                        engine.deleteDict(dictName)
                                        refresh()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                                if (!isPitchDictionary) {
                                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Collapse dictionary block", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        Switch(checked = blockCollapseEnabled, onCheckedChange = { enabled ->
                                            engine.setDictionaryBlockCollapseEnabled(dictName, enabled)
                                            blockCollapseByDict = blockCollapseByDict + (dictName to enabled)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
