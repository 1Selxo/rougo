package com.selxo.rougo.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selxo.rougo.dictionary.DeinflectorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val MAX_CJK_SUBTITLE_LOOKUP_CHARS = 8
private const val MAX_CONTEXT_LOOKUP_STARTS = 8
private val RIGHT_CONTEXT_LOOKUP_LANGUAGES = setOf("de", "en")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClickableSubtitleText(
    text: String,
    targetLanguage: String,
    onWordClicked: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().padding(18.dp),
    fontSize: TextUnit = 28.sp,
    textColor: Color = Color.White,
    resolveLookupSelection: suspend (String, Int, String) -> DictionaryLookupSelection? = { source, offset, language ->
        extractDictionaryLookupSelection(source, offset, language)
    }
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var highlightedSelection by remember(text) { mutableStateOf<DictionaryLookupSelection?>(null) }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    val displayText = remember(text, highlightedSelection, highlightColor) {
        buildAnnotatedString {
            append(text)
            highlightedSelection?.let { selection ->
                val start = selection.startIndex.coerceIn(0, text.length)
                val end = selection.endIndex.coerceIn(start, text.length)
                if (end > start) addStyle(SpanStyle(background = highlightColor), start, end)
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val minTextWidth = maxWidth
        Box(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
            ) {
                Text(
                    text = displayText,
                    color = textColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .widthIn(min = minTextWidth)
                        .pointerInput(text, targetLanguage) {
                            detectTapGestures { position ->
                                val offset = layoutResult?.getOffsetForPosition(position) ?: return@detectTapGestures
                                scope.launch {
                                    val selection = resolveLookupSelection(text, offset, targetLanguage)
                                        ?: return@launch
                                    highlightedSelection = selection
                                    onWordClicked(selection.query)
                                }
                            }
                        },
                    onTextLayout = { layoutResult = it }
                )
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
            )
        }
    }
}

data class DictionaryLookupSelection(
    val query: String,
    val startIndex: Int,
    val endIndex: Int
)

fun extractDictionaryLookupText(
    text: String,
    offset: Int,
    targetLanguage: String = "ja"
): String {
    return extractDictionaryLookupSelection(text, offset, targetLanguage)?.query.orEmpty()
}

fun extractDictionaryLookupSelection(
    text: String,
    offset: Int,
    targetLanguage: String = "ja"
): DictionaryLookupSelection? {
    if (text.isBlank()) return null
    val safeOffset = offset.coerceIn(0, text.lastIndex)
    val char = text[safeOffset]
    if (isCjkLookupChar(char)) {
        val end = cjkLookupEnd(text, safeOffset)
        return DictionaryLookupSelection(text.substring(safeOffset, end), safeOffset, end)
    }

    if (!isWordLookupChar(char)) return null
    var start = safeOffset
    var end = safeOffset + 1
    while (start > 0 && isWordLookupChar(text[start - 1])) start--
    while (end < text.length && isWordLookupChar(text[end])) end++
    if (targetLanguage.normalizedLookupLanguage() in RIGHT_CONTEXT_LOOKUP_LANGUAGES) {
        end = rightContextEnd(text, end)
    }
    val query = text.substring(start, end).trim()
    if (query.isBlank()) return null
    return DictionaryLookupSelection(query, start, end)
}

fun subtitleLookupSelectionCandidates(
    text: String,
    offset: Int,
    targetLanguage: String = "ja"
): List<DictionaryLookupSelection> {
    if (text.isBlank()) return emptyList()
    val safeOffset = offset.coerceIn(0, text.lastIndex)
    val char = text[safeOffset]
    if (!isCjkLookupChar(char)) {
        return listOfNotNull(extractDictionaryLookupSelection(text, safeOffset, targetLanguage))
    }

    var runStart = safeOffset
    var runEnd = safeOffset + 1
    while (runStart > 0 && isCjkLookupChar(text[runStart - 1])) runStart--
    while (runEnd < text.length && isCjkLookupChar(text[runEnd])) runEnd++

    val firstStart = runStart.coerceAtLeast(safeOffset - MAX_CONTEXT_LOOKUP_STARTS + 1)
    return (safeOffset downTo firstStart)
        .map { start ->
            val end = cjkLookupEnd(text, start, runEnd)
            DictionaryLookupSelection(text.substring(start, end), start, end)
        }
        .filter { it.query.isNotBlank() }
        .distinctBy { it.startIndex to it.query }
}

suspend fun DictionaryEngine.resolveSubtitleLookupSelection(
    text: String,
    offset: Int,
    targetLanguage: String = "ja"
): DictionaryLookupSelection? {
    val candidates = subtitleLookupSelectionCandidates(text, offset, targetLanguage)
    if (candidates.isEmpty()) return null

    val safeOffset = offset.coerceIn(0, text.lastIndex)
    if (!isCjkLookupChar(text[safeOffset])) return candidates.first()

    var bestSelection: DictionaryLookupSelection? = null
    var bestLength = 0
    candidates.forEach { candidate ->
        val matched = searchPrefixes(candidate.query)
            .firstOrNull()
            ?.term
            ?.takeIf { it.isNotBlank() && candidate.query.startsWith(it) }
            ?: return@forEach
        val end = candidate.startIndex + matched.length
        if (safeOffset in candidate.startIndex until end && matched.length > bestLength) {
            bestSelection = DictionaryLookupSelection(
                query = text.substring(candidate.startIndex, end),
                startIndex = candidate.startIndex,
                endIndex = end
            )
            bestLength = matched.length
        }
    }

    return bestSelection ?: candidates.first()
}

private fun cjkLookupEnd(text: String, start: Int, runEnd: Int = text.length): Int {
    val limit = minOf(runEnd, text.length, start + MAX_CJK_SUBTITLE_LOOKUP_CHARS)
    var end = start + 1
    while (end < limit && isCjkLookupChar(text[end])) end++
    return end
}

private fun isWordLookupChar(char: Char): Boolean =
    char.isLetterOrDigit() || char == '\'' || char == '\u2019' || char == '-' || char == '\u2010' || char == '\u2011'

private fun rightContextEnd(text: String, initialEnd: Int): Int {
    var end = initialEnd
    var tokenCount = 0
    while (end < text.length && tokenCount < 4) {
        var cursor = end
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length) break
        if (text[cursor] == '.') break
        while (cursor < text.length && (isWordLookupChar(text[cursor]) || isAbbreviationPeriod(text, cursor))) cursor++
        if (cursor == end) break
        end = cursor
        tokenCount++
    }
    return end
}

private fun isAbbreviationPeriod(text: String, periodIndex: Int): Boolean {
    if (text.getOrNull(periodIndex) != '.') return false
    val prev = text.getOrNull(periodIndex - 1)
    val next = text.getOrNull(periodIndex + 1)
    return prev?.isLetter() == true && (next?.isLetter() == true || next?.isWhitespace() == true)
}

private fun String.normalizedLookupLanguage(): String = lowercase().substringBefore('-')

private fun isCjkLookupChar(char: Char): Boolean {
    val block = Character.UnicodeBlock.of(char)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoshiDictionaryBottomSheet(
    query: String,
    engine: DictionaryEngine,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchText by remember(query) { mutableStateOf(query) }
    var results by remember(query) { mutableStateOf<List<DictEntry>>(emptyList()) }
    var isLoading by remember(query) { mutableStateOf(true) }
    var targetLanguage by remember { mutableStateOf(engine.getTargetLanguage()) }
    var showTargetLanguageMenu by remember { mutableStateOf(false) }
    val targetLanguageCode = remember(targetLanguage) {
        DeinflectorRegistry.normalize(targetLanguage).uppercase(Locale.ROOT).take(2)
    }

    LaunchedEffect(searchText, targetLanguage) {
        isLoading = true
        results = withContext(Dispatchers.IO) { engine.searchPrefixes(searchText) }
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    label = { Text("Search Dictionary") },
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(
                        onClick = { showTargetLanguageMenu = true },
                        modifier = Modifier.height(56.dp).width(56.dp)
                    ) {
                        Text(targetLanguageCode, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = showTargetLanguageMenu, onDismissRequest = { showTargetLanguageMenu = false }) {
                        DeinflectorRegistry.languageOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    targetLanguage = option.code
                                    engine.setTargetLanguage(option.code)
                                    showTargetLanguageMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                isLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                results.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No results found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    DictionaryResultsList(
                        results = results,
                        engine = engine,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun HoshiDictionaryPanel(
    query: String,
    engine: DictionaryEngine,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchText by remember(query) { mutableStateOf(query) }
    var results by remember(query) { mutableStateOf<List<DictEntry>>(emptyList()) }
    var isLoading by remember(query) { mutableStateOf(true) }
    var targetLanguage by remember { mutableStateOf(engine.getTargetLanguage()) }
    var showTargetLanguageMenu by remember { mutableStateOf(false) }
    val targetLanguageCode = remember(targetLanguage) {
        DeinflectorRegistry.normalize(targetLanguage).uppercase(Locale.ROOT).take(2)
    }

    LaunchedEffect(searchText, targetLanguage) {
        isLoading = true
        results = withContext(Dispatchers.IO) { engine.searchPrefixes(searchText) }
        isLoading = false
    }

    Column(modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotBlank()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                label = { Text("Search Dictionary") },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box {
                TextButton(
                    onClick = { showTargetLanguageMenu = true },
                    modifier = Modifier.height(56.dp).width(56.dp)
                ) {
                    Text(targetLanguageCode, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = showTargetLanguageMenu, onDismissRequest = { showTargetLanguageMenu = false }) {
                    DeinflectorRegistry.languageOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                targetLanguage = option.code
                                engine.setTargetLanguage(option.code)
                                showTargetLanguageMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            isLoading -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            results.isEmpty() -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text("No results found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                DictionaryResultsList(
                    results = results,
                    engine = engine,
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
            }
        }
    }
}

@Composable
private fun DictionaryResultsList(
    results: List<DictEntry>,
    engine: DictionaryEngine,
    modifier: Modifier = Modifier
) {
    val grouped = remember(results) { results.groupBy { "${it.deinflected}|${it.reading}" }.values.toList() }
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(end = 12.dp)
        ) {
            items(grouped, key = { entries ->
                val first = entries.first()
                "${first.deinflected}|${first.reading}|${first.dictName}|${entries.size}"
            }) { entries ->
                DictGroupCard(entries, engine)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

@Composable
fun PitchOverline(reading: String, pitchPosition: Int) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val annotationColor = MaterialTheme.colorScheme.onSurfaceVariant
    val morae = remember(reading) { splitJapaneseMorae(reading) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        morae.forEachIndexed { index, mora ->
            val isHigh = when (pitchPosition) {
                0 -> index > 0
                1 -> index == 0
                else -> index > 0 && index < pitchPosition
            }
            val hasDrop = pitchPosition > 0 && index == pitchPosition - 1

            Box(contentAlignment = Alignment.TopStart) {
                Text(mora, color = textColor, fontSize = 20.sp)
                Canvas(modifier = Modifier.matchParentSize()) {
                    if (isHigh) {
                        drawLine(
                            textColor,
                            Offset(0f, 2.dp.toPx()),
                            Offset(size.width, 2.dp.toPx()),
                            1.5.dp.toPx()
                        )
                    }
                    if (hasDrop) {
                        drawLine(
                            textColor,
                            Offset(size.width, 2.dp.toPx()),
                            Offset(size.width, size.height * 0.6f),
                            1.5.dp.toPx()
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("[$pitchPosition]", color = annotationColor, fontSize = 14.sp)
    }
}

@Composable
fun PitchDiagram(reading: String, pitchPosition: Int, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val morae = remember(reading) { splitJapaneseMorae(reading) }
    if (morae.isEmpty()) return

    val dotRadius = 4.dp
    val strokeWidth = 2.dp
    val moraWidth = 32.dp

    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Canvas(
            modifier = Modifier
                .width(moraWidth * (morae.size + 1))
                .height(40.dp)
        ) {
            val stepX = moraWidth.toPx()
            val highY = 10.dp.toPx()
            val lowY = 30.dp.toPx()

            val points = morae.mapIndexed { index, _ ->
                val isHigh = when (pitchPosition) {
                    0 -> index > 0
                    1 -> index == 0
                    else -> index > 0 && index < pitchPosition
                }
                Offset(index * stepX + stepX / 2, if (isHigh) highY else lowY)
            }

            val particleHigh = pitchPosition == 0 || (pitchPosition > 0 && morae.size < pitchPosition)
            val particlePoint = Offset(morae.size * stepX + stepX / 2, if (particleHigh) highY else lowY)

            for (i in 0 until points.size - 1) {
                drawLine(color = textColor, start = points[i], end = points[i + 1], strokeWidth = strokeWidth.toPx())
            }
            if (points.isNotEmpty()) {
                drawLine(
                    color = textColor.copy(alpha = 0.5f),
                    start = points.last(),
                    end = particlePoint,
                    strokeWidth = strokeWidth.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                )
            }

            points.forEachIndexed { index, point ->
                if (index == 0) {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = point, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                } else {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = point)
                }
            }

            val trianglePath = Path().apply {
                moveTo(particlePoint.x, particlePoint.y - 4.dp.toPx())
                lineTo(particlePoint.x - 4.dp.toPx(), particlePoint.y + 4.dp.toPx())
                lineTo(particlePoint.x + 4.dp.toPx(), particlePoint.y + 4.dp.toPx())
                close()
            }
            drawPath(path = trianglePath, color = textColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }

        Row(modifier = Modifier.width(moraWidth * (morae.size + 1))) {
            morae.forEach { mora ->
                Text(
                    text = mora,
                    fontSize = 12.sp,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(moraWidth),
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(Modifier.width(moraWidth))
        }
    }
}

@Composable
fun DictGroupCard(entries: List<DictEntry>, engine: DictionaryEngine) {
    val first = entries.firstOrNull() ?: return
    val colorScheme = MaterialTheme.colorScheme
    val chipContainer = colorScheme.primary.copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.12f)

    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(first.deinflected, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                if (first.reading.isNotBlank() && first.reading != first.deinflected) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        first.reading,
                        fontSize = 18.sp,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            if (first.pitchPositions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                first.pitchPositions.groupBy { it.dictName }.forEach { (dictName, pitches) ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Surface(
                            color = chipContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(dictName, color = colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        pitches.forEach { pitch ->
                            PitchOverline(reading = first.reading, pitchPosition = pitch.position)
                            Spacer(Modifier.height(8.dp))
                            PitchDiagram(reading = first.reading, pitchPosition = pitch.position)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            entries.groupBy { it.dictName }.forEach { (dictName, dictEntries) ->
                val blockCollapseEnabled = remember(dictName) {
                    engine.isDictionaryBlockCollapseEnabled(dictName)
                }
                DictionaryEntrySection(
                    dictName = dictName,
                    entries = dictEntries,
                    blockCollapseEnabled = blockCollapseEnabled,
                    chipContainer = chipContainer
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryEntrySection(
    dictName: String,
    entries: List<DictEntry>,
    blockCollapseEnabled: Boolean,
    chipContainer: Color
) {
    var expanded by remember(dictName, entries) { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val sourceText = remember(entries) { dictionarySourcePlainText(entries.map { it.definition }) }
    val preview = remember(sourceText) { firstDictionaryDefinitionLine(sourceText) }
    val expandable = remember(sourceText, preview) { isExpandableDictionarySource(sourceText, preview) }
    val blockCanCollapse = blockCollapseEnabled && expandable
    val showBody = !blockCanCollapse || expanded

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = blockCanCollapse) { expanded = !expanded }
                .padding(vertical = 4.dp)
        ) {
            Surface(color = chipContainer, shape = RoundedCornerShape(4.dp)) {
                Text(
                    text = dictName,
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            if (showBody) {
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    preview,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (blockCanCollapse) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (showBody && sourceText.isNotBlank()) {
            DictionaryDefinitionBody(
                entries = entries,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun DictionaryDefinitionBody(
    entries: List<DictEntry>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val definitions = remember(entries) {
        entries.filter { it.definition.trim().isNotBlank() }
    }

    Column(modifier = modifier) {
        definitions.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            val tags = remember(entry.definitionTags, entry.termTags) { dictionaryEntryTags(entry) }
            if (tags.isNotEmpty()) {
                DictionaryDefinitionTags(tags)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = dictionaryDefinitionDisplayText(entry.definition),
                color = color,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DictionaryDefinitionTags(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        tags.forEach { tag -> DictionaryTagChip(tag = tag) }
    }
}

@Composable
private fun DictionaryTagChip(tag: String) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.inverseSurface.copy(alpha = if (isSystemInDarkTheme()) 0.72f else 0.82f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = tag,
            color = colorScheme.inverseOnSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

private fun dictionaryDefinitionDisplayText(definition: String): String {
    val trimmed = definition.trim()
    if (!trimmed.isStructuredJsonCandidate()) return cleanDictionaryDefinitionLine(trimmed)
    return runCatching {
        val node: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        dictionaryNodePlainText(node).trim()
    }.getOrElse { cleanDictionaryDefinitionLine(trimmed) }
}

private fun dictionaryEntryTags(entry: DictEntry): List<String> =
    "${entry.definitionTags} ${entry.termTags}"
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun dictionarySourcePlainText(processedDefinitions: List<String>): String =
    processedDefinitions
        .asSequence()
        .map { dictionaryDefinitionPlainText(it).trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

private fun firstDictionaryDefinitionLine(sourceText: String): String =
    sourceText
        .lineSequence()
        .map { cleanDictionaryDefinitionLine(it) }
        .firstOrNull { it.isNotBlank() }
        ?.takeIf { it.isNotBlank() }
        ?: "Definition"

private fun isExpandableDictionarySource(sourceText: String, preview: String): Boolean {
    val nonBlankLines = sourceText
        .lineSequence()
        .map { cleanDictionaryDefinitionLine(it) }
        .filter { it.isNotBlank() }
        .toList()
    val compactText = nonBlankLines.joinToString(" ")
    return nonBlankLines.size > 1 ||
        compactText.length > preview.length ||
        compactText.length > 72
}

private fun dictionaryDefinitionPlainText(processedDefinition: String): String =
    dictionaryDefinitionDisplayText(processedDefinition).replace('\u00A0', ' ')

private fun Any?.letJsonChildren(): List<Any?> = when (this) {
    is JSONArray -> List(length()) { opt(it) }
    is JSONObject -> {
        val content = opt("content")
        when (content) {
            is JSONArray -> List(content.length()) { content.opt(it) }
            null -> listOf(optString("tag").takeIf { it.isNotBlank() })
            else -> listOf(content)
        }
    }
    null -> emptyList()
    else -> listOf(this)
}

private fun dictionaryNodePlainText(node: Any?): String {
    return when (node) {
        is JSONArray -> List(node.length()) { dictionaryNodePlainText(node.opt(it)) }.joinToString("\n")
        is JSONObject -> node.letJsonChildren().joinToString(" ") { dictionaryNodePlainText(it) }
        null -> ""
        else -> node.toString()
    }.replace(Regex("\\s+"), " ").trim()
}

private fun String.isStructuredJsonCandidate(): Boolean {
    val trimmed = trim()
    return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))
}

private fun cleanDictionaryDefinitionLine(line: String): String =
    line.replace(Regex("<[^>]+>"), "").replace("\\n", "\n").trim()

private fun splitJapaneseMorae(reading: String): List<String> {
    if (reading.isBlank()) return emptyList()
    val smallKana = setOf('ゃ', 'ゅ', 'ょ', 'ャ', 'ュ', 'ョ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ')
    val result = mutableListOf<String>()
    reading.forEach { char ->
        if (char in smallKana && result.isNotEmpty()) {
            result[result.lastIndex] = result.last() + char
        } else {
            result += char.toString()
        }
    }
    return result
}
