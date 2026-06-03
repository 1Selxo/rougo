package com.selxo.rougo

import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.selxo.rougo.dictionary.DeinflectorRegistry
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_RIGHT_CONTEXT_CHARS = 96
private const val MAX_RIGHT_CONTEXT_WORDS = 12
private val RIGHT_CONTEXT_LOOKUP_LANGUAGES = setOf("de", "en")
private val PERIOD_ABBREVIATIONS = setOf(
    "bzw.",
    "bspw.",
    "ca.",
    "d.h.",
    "dr.",
    "etc.",
    "evtl.",
    "ggf.",
    "inkl.",
    "i.d.r.",
    "m.e.",
    "nr.",
    "prof.",
    "s.",
    "sog.",
    "u.a.",
    "u.u.",
    "usw.",
    "v.a.",
    "vgl.",
    "z.b.",
    "z.t.",
    "zzgl.",
)
private val INITIAL_ABBREVIATION_REGEX = Regex("^(?:\\p{L}{1,3}\\.){2,}$")

@Composable
fun JapaneseClickableSubtitle(
    text: String,
    targetLanguage: String = "ja",
    onWordClicked: (String) -> Unit
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 44.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(16.dp)
            .pointerInput(text, targetLanguage) {
                detectTapGestures { pos ->
                    layoutResult?.let { layout ->
                        val offset = layout.getOffsetForPosition(pos)
                        if (offset < text.length) {
                            val clickedText = extractDictionaryLookupText(text, offset, targetLanguage)
                            if (clickedText.isNotBlank()) {
                                onWordClicked(clickedText)
                            }
                        }
                    }
                }
            },
        onTextLayout = { layoutResult = it }
    )
}

internal fun extractDictionaryLookupText(
    text: String,
    offset: Int,
    languageCode: String = "ja"
): String {
    if (text.isEmpty()) return ""
    val safeOffset = offset.coerceIn(0, text.lastIndex)
    val char = text[safeOffset]
    if (isWordLookupChar(char) && !isCjkLookupChar(char)) {
        var start = safeOffset
        var end = safeOffset + 1
        while (start > 0 && isWordLookupChar(text[start - 1])) start--
        while (end < text.length && isWordLookupChar(text[end])) end++
        if (languageCode.normalizedLookupLanguage() in RIGHT_CONTEXT_LOOKUP_LANGUAGES) {
            end = rightContextEnd(text, end)
        }
        return text.substring(start, end)
    }

    val endIndex = minOf(text.length, safeOffset + 8)
    return text.substring(safeOffset, endIndex)
}

private fun isWordLookupChar(char: Char): Boolean {
    return char.isLetterOrDigit() || char == '\'' || char == '\u2019' || char == '-' || char == '\u2010' || char == '\u2011'
}

private fun rightContextEnd(text: String, initialEnd: Int): Int {
    var end = initialEnd
    var words = 1

    while (end < text.length && end - initialEnd < MAX_RIGHT_CONTEXT_CHARS && words < MAX_RIGHT_CONTEXT_WORDS) {
        val char = text[end]
        if (char == '\n' || char == '\r' || char == '?' || char == '!' || char == ';' || char == ':') break
        if (char == '.' && !isAbbreviationPeriod(text, end)) break
        if (isWordLookupChar(char)) {
            words++
            while (end < text.length && isWordLookupChar(text[end])) end++
        } else {
            end++
        }
    }

    return end
}

private fun isAbbreviationPeriod(text: String, periodIndex: Int): Boolean {
    var start = abbreviationTokenStart(text, periodIndex)
    while (start > 0) {
        var spaceStart = start
        while (spaceStart > 0 && text[spaceStart - 1].isWhitespace()) spaceStart--
        if (spaceStart == start || spaceStart == 0 || text[spaceStart - 1] != '.') break
        start = abbreviationTokenStart(text, spaceStart - 1)
    }

    var end = periodIndex + 1
    while (end < text.length && isAbbreviationChar(text[end])) end++
    while (end < text.length) {
        var tokenStart = end
        while (tokenStart < text.length && text[tokenStart].isWhitespace()) tokenStart++

        var tokenEnd = tokenStart
        while (tokenEnd < text.length && text[tokenEnd].isLetterOrDigit()) tokenEnd++

        if (tokenEnd == tokenStart || tokenEnd - tokenStart > 3 || tokenEnd >= text.length || text[tokenEnd] != '.') break
        end = tokenEnd + 1
        while (end < text.length && isAbbreviationChar(text[end])) end++
    }

    val candidate = text.substring(start, end).lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    return candidate in PERIOD_ABBREVIATIONS || INITIAL_ABBREVIATION_REGEX.matches(candidate)
}

private fun abbreviationTokenStart(text: String, index: Int): Int {
    var start = index
    while (start > 0 && isAbbreviationChar(text[start - 1])) start--
    return start
}

private fun isAbbreviationChar(char: Char): Boolean {
    return char.isLetterOrDigit() || char == '.'
}

private fun String.normalizedLookupLanguage(): String {
    return lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
}

private fun isCjkLookupChar(char: Char): Boolean {
    return when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}

@Composable
fun PitchOverline(reading: String, pitchPosition: Int) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val annotationColor = MaterialTheme.colorScheme.onSurfaceVariant
    val morae = remember(reading) { splitJapaneseMorae(reading) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        morae.forEachIndexed { i, mora ->
            val isHigh = when (pitchPosition) {
                0 -> i > 0
                1 -> i == 0
                else -> i > 0 && i < pitchPosition
            }
            val hasDrop = pitchPosition > 0 && i == pitchPosition - 1

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

    val dotRadius = 4.dp
    val strokeWidth = 2.dp
    val moraWidth = 32.dp

    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Canvas(
            modifier = Modifier
                .width((moraWidth * (morae.size + 1)))
                .height(40.dp)
        ) {
            val stepX = moraWidth.toPx()
            val highY = 10.dp.toPx()
            val lowY = 30.dp.toPx()

            val points = morae.mapIndexed { i, _ ->
                val isHigh = when (pitchPosition) {
                    0 -> i > 0
                    1 -> i == 0
                    else -> if (i == 0) false else i < pitchPosition
                }
                Offset(i * stepX + stepX / 2, if (isHigh) highY else lowY)
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

            points.forEachIndexed { i, pt ->
                if (i == 0) {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = pt, style = Stroke(width = 1.dp.toPx()))
                } else {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = pt)
                }
            }

            val trianglePath = Path().apply {
                moveTo(particlePoint.x, particlePoint.y - 4.dp.toPx())
                lineTo(particlePoint.x - 4.dp.toPx(), particlePoint.y + 4.dp.toPx())
                lineTo(particlePoint.x + 4.dp.toPx(), particlePoint.y + 4.dp.toPx())
                close()
            }
            drawPath(path = trianglePath, color = textColor, style = Stroke(width = 1.dp.toPx()))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HoshiDictionaryBottomSheet(query: String, engine: DictionaryEngine, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val colorScheme = MaterialTheme.colorScheme
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    val resultsListState = rememberLazyListState()
    var routeExpandedTopDragToSheet by remember { mutableStateOf(false) }

    fun listIsAtTop(): Boolean {
        return resultsListState.firstVisibleItemIndex == 0 &&
            resultsListState.firstVisibleItemScrollOffset == 0
    }

    LaunchedEffect(
        sheetState.currentValue,
        resultsListState.firstVisibleItemIndex,
        resultsListState.firstVisibleItemScrollOffset
    ) {
        if (sheetState.currentValue != SheetValue.Expanded || !listIsAtTop()) {
            routeExpandedTopDragToSheet = false
        }
    }

    val expandedTopDragRouter = remember(sheetState, resultsListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                if (available.y < 0f) {
                    routeExpandedTopDragToSheet = false
                    return Offset.Zero
                }

                val shouldRouteToSheet =
                    available.y > 0f &&
                        listIsAtTop() &&
                        sheetState.currentValue == SheetValue.Expanded &&
                        sheetState.targetValue == SheetValue.Expanded

                if (!shouldRouteToSheet) {
                    routeExpandedTopDragToSheet = false
                    return Offset.Zero
                }

                val wasRouting = routeExpandedTopDragToSheet
                routeExpandedTopDragToSheet = true
                return if (wasRouting) Offset.Zero else Offset(x = 0f, y = available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                routeExpandedTopDragToSheet = false
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                routeExpandedTopDragToSheet = false
                return Velocity.Zero
            }
        }
    }

    val defaultFlingBehavior = ScrollableDefaults.flingBehavior()
    val containTopBoundListFling = remember(resultsListState, defaultFlingBehavior) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val startedAtTop = listIsAtTop()
                val remainingVelocity = with(defaultFlingBehavior) {
                    performFling(initialVelocity)
                }
                return if (!startedAtTop && listIsAtTop()) 0f else remainingVelocity
            }
        }
    }

    var results by remember { mutableStateOf<List<DictEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf(query) }
    var isSearching by remember { mutableStateOf(false) }
    var targetLanguage by remember { mutableStateOf(engine.getTargetLanguage()) }
    var showTargetLanguageMenu by remember { mutableStateOf(false) }
    val targetLanguageCode = remember(targetLanguage) {
        DeinflectorRegistry.normalize(targetLanguage).uppercase(Locale.ROOT).take(2)
    }

    LaunchedEffect(searchQuery, targetLanguage) {
        isSearching = true
        results = engine.searchPrefixes(searchQuery)
        if (resultsListState.firstVisibleItemIndex != 0 || resultsListState.firstVisibleItemScrollOffset != 0) {
            resultsListState.scrollToItem(0)
        }
        isSearching = false
    }

    BackHandler(enabled = true) {
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(maxSheetHeight).padding(16.dp).padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Dictionary") },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface,
                        focusedContainerColor = colorScheme.surface,
                        unfocusedContainerColor = colorScheme.surface,
                        focusedLabelColor = colorScheme.primary,
                        unfocusedLabelColor = colorScheme.onSurfaceVariant,
                        focusedBorderColor = colorScheme.primary,
                        unfocusedBorderColor = colorScheme.onSurfaceVariant,
                        cursorColor = colorScheme.primary
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    TextButton(
                        onClick = { showTargetLanguageMenu = true },
                        modifier = Modifier.height(56.dp).width(56.dp)
                    ) {
                        Text(
                            targetLanguageCode,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    DropdownMenu(
                        expanded = showTargetLanguageMenu,
                        onDismissRequest = { showTargetLanguageMenu = false }
                    ) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isSearching -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                results.isEmpty() -> Text(
                    "No results found.",
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                else -> {
                    val grouped = results.groupBy { "${it.deinflected}|${it.reading}" }
                    CompositionLocalProvider(LocalOverscrollFactory provides null) {
                        LazyColumn(
                            state = resultsListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .nestedScroll(expandedTopDragRouter)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                        } while (event.changes.any { it.pressed })
                                        routeExpandedTopDragToSheet = false
                                    }
                                },
                            flingBehavior = containTopBoundListFling,
                            userScrollEnabled = !routeExpandedTopDragToSheet,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            grouped.forEach { (_, groupEntries) ->
                                item {
                                    DictGroupCard(groupEntries, engine)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DictGroupCard(entries: List<DictEntry>, engine: DictionaryEngine) {
    val first = entries.first()
    val colorScheme = MaterialTheme.colorScheme
    val chipContainer = colorScheme.primary.copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.12f)

    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(first.deinflected, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)

                if (first.reading.isNotEmpty() && first.reading != first.deinflected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "【${first.reading}】",
                        fontSize = 18.sp,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            if (first.pitchPositions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                val pitchesByDict = first.pitchPositions.groupBy { it.dictName }
                pitchesByDict.forEach { (dictName, pitches) ->
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
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

            Spacer(modifier = Modifier.height(12.dp))

            val byDict = entries.groupBy { it.dictName }
            byDict.forEach { (dictName, dictEntries) ->
                val blockCollapseEnabled = remember(dictName) {
                    engine.isDictionaryBlockCollapseEnabled(dictName)
                }
                DictionaryEntrySection(
                    dictName = dictName,
                    entries = dictEntries,
                    blockCollapseEnabled = blockCollapseEnabled,
                    chipContainer = chipContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

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

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = blockCanCollapse) { expanded = !expanded }
                .padding(vertical = 4.dp)
        ) {
            Surface(
                color = chipContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = dictName,
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (showBody) {
                Spacer(modifier = Modifier.weight(1f))
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
                    contentDescription = if (expanded) "Collapse dictionary source" else "Expand dictionary source",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (showBody && sourceText.isNotBlank()) {
            DictionaryDefinitionBody(
                entries = entries,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp)
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
            val definition = entry.definition.trim()
            val structuredNodes = remember(definition) { parseStructuredDefinition(definition) }
            val displayTags = remember(entry.definitionTags, entry.termTags) { dictionaryEntryTags(entry) }

            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            if (displayTags.isNotEmpty()) {
                DictionaryDefinitionTags(displayTags)
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (structuredNodes != null) {
                DictionaryStructuredContent(
                    nodes = structuredNodes,
                    color = color
                )
            } else {
                val displayDefinition = if (definition.isStructuredJsonCandidate()) {
                    convertStructuredToHtml(definition)
                } else {
                    definition
                }
                DictionaryHtmlText(definition = displayDefinition, color = color)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryDefinitionTags(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        tags.forEach { tag ->
            DictionaryTagChip(tag = tag)
        }
    }
}

@Composable
private fun DictionaryTagChip(tag: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.inverseSurface.copy(alpha = if (isSystemInDarkTheme()) 0.72f else 0.82f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
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

@Composable
private fun DictionaryStructuredContent(
    nodes: List<DictionaryNode>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        nodes.forEach { node ->
            DictionaryStructuredNode(
                node = node,
                color = color
            )
        }
    }
}

@Composable
private fun DictionaryStructuredNode(
    node: DictionaryNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    when (node) {
        is DictionaryTextNode -> {
            val text = node.text.trim()
            if (text.isNotBlank()) {
                DictionaryInlineText(nodes = listOf(node), color = color, modifier = modifier)
            }
        }
        is DictionaryElementNode -> when {
            node.shouldHideInCompactDictionaryEntry() -> Unit
            node.isGrammarDetails() -> DictionaryGrammarLine(
                element = node,
                color = color,
                modifier = modifier
            )
            node.tag == "details" -> DictionaryDetailsBlock(
                element = node,
                color = color,
                modifier = modifier
            )
            node.tag == "ol" -> DictionaryOrderedList(
                element = node,
                color = color,
                modifier = modifier
            )
            node.tag == "ul" && node.dataContent == "glosses" -> DictionaryGlossList(
                element = node,
                color = color,
                modifier = modifier
            )
            node.tag == "ul" -> DictionaryUnorderedList(
                element = node,
                color = color,
                modifier = modifier
            )
            node.dataContent == "extra-info" -> DictionaryStructuredContent(
                nodes = node.children,
                color = color,
                modifier = modifier.padding(start = 6.dp)
            )
            node.dataContent == "backlink" -> DictionaryBacklinkRow(element = node, modifier = modifier)
            node.dataContent == "tags" -> DictionaryTagRow(nodes = node.children, modifier = modifier)
            node.tag == "li" -> DictionaryGenericListItem(
                element = node,
                color = color,
                modifier = modifier
            )
            node.tag == "br" -> Spacer(modifier = modifier.height(4.dp))
            else -> DictionaryMixedBlock(
                nodes = node.children,
                color = color,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun DictionaryMixedBlock(
    nodes: List<DictionaryNode>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val inlineBuffer = mutableListOf<DictionaryNode>()
        nodes.forEach { child ->
            if (child.isStandaloneDictionaryBlock()) {
                if (inlineBuffer.any { dictionaryNodePlainText(it).isNotBlank() }) {
                    DictionaryInlineText(nodes = inlineBuffer.toList(), color = color)
                    inlineBuffer.clear()
                }
                DictionaryStructuredNode(
                    node = child,
                    color = color
                )
            } else {
                inlineBuffer += child
            }
        }
        if (inlineBuffer.any { dictionaryNodePlainText(it).isNotBlank() }) {
            DictionaryInlineText(nodes = inlineBuffer.toList(), color = color)
        }
    }
}

@Composable
private fun DictionaryGrammarLine(
    element: DictionaryElementNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    val summary = element.children.firstOrNull {
        it is DictionaryElementNode && it.tag == "summary"
    } as? DictionaryElementNode
    val summaryText = summary?.children?.let { dictionaryNodesPlainText(it) }?.trim()
        ?.ifBlank { null }
        ?: "Grammar"
    val bodyText = element.children
        .filterNot { it === summary }
        .let { dictionaryNodesPlainText(it) }
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
    if (bodyText.isBlank()) return

    Text(
        text = buildAnnotatedString {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(summaryText)
            append(": ")
            pop()
            append(bodyText)
        },
        color = color,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun DictionaryDetailsBlock(
    element: DictionaryElementNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    val summary = element.children.firstOrNull {
        it is DictionaryElementNode && it.tag == "summary"
    } as? DictionaryElementNode
    val summaryText = summary?.children?.let { dictionaryNodesPlainText(it) }?.trim()
        ?.ifBlank { null }
        ?: "Details"
    val bodyNodes = element.children.filterNot { it === summary }
    var expanded by remember(element) { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val isSectionSummary = summaryText.equals("Grammar", ignoreCase = true) ||
        summaryText.equals("Etymology", ignoreCase = true)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 2.dp, vertical = 3.dp)
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $summaryText" else "Expand $summaryText",
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = summaryText,
                color = if (expanded) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = if (isSectionSummary) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (expanded && bodyNodes.isNotEmpty()) {
            DictionaryStructuredContent(
                nodes = bodyNodes,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun DictionaryOrderedList(
    element: DictionaryElementNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (element.dataContent == "glosses") {
        DictionaryGlossList(
            element = element,
            color = color,
            modifier = modifier
        )
        return
    }

    val items = element.children.filterIsInstance<DictionaryElementNode>().filter { it.tag == "li" }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}.",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.width(24.dp)
                )
                DictionaryStructuredContent(
                    nodes = item.children,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DictionaryGlossList(
    element: DictionaryElementNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    val items = element.children.filterIsInstance<DictionaryElementNode>().filter { it.tag == "li" }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            DictionaryGlossListItem(
                item = item,
                index = index,
                color = color
            )
        }
    }
}

@Composable
private fun DictionaryGlossListItem(
    item: DictionaryElementNode,
    index: Int,
    color: Color
) {
    val contentNodes = item.singleChildElement("div")?.children ?: item.children
    val tagNodes = contentNodes
        .filterIsInstance<DictionaryElementNode>()
        .filter { it.dataContent == "tags" }
    val tagTexts = tagNodes
        .flatMap { it.children }
        .map { dictionaryNodePlainText(it).trim() }
        .filter { it.isNotBlank() }
    val nonTagNodes = contentNodes.filterNot { it in tagNodes }
    val firstBlockIndex = nonTagNodes.indexOfFirst { it.isSenseDetailBlock() }
    val inlineNodes = if (firstBlockIndex == -1) nonTagNodes else nonTagNodes.take(firstBlockIndex)
    val blockNodes = if (firstBlockIndex == -1) emptyList() else nonTagNodes.drop(firstBlockIndex)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "${index + 1}.",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.width(24.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                tagTexts.forEach { tag ->
                    DictionaryTagChip(tag = tag)
                }
                if (inlineNodes.any { dictionaryNodePlainText(it).isNotBlank() }) {
                    DictionaryInlineText(
                        nodes = inlineNodes,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fillMaxWidth = false
                    )
                }
            }
            blockNodes.filterNot { it.shouldHideInCompactDictionaryEntry() }.forEach { block ->
                DictionaryStructuredNode(
                    node = block,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun DictionaryUnorderedList(
    element: DictionaryElementNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    val items = element.children.filterIsInstance<DictionaryElementNode>().filter { it.tag == "li" }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text("•", color = color, fontSize = 14.sp, modifier = Modifier.width(18.dp))
                DictionaryStructuredContent(
                    nodes = item.children,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DictionaryGenericListItem(
    element: DictionaryElementNode,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text("•", color = color, fontSize = 14.sp, modifier = Modifier.width(18.dp))
        DictionaryStructuredContent(
            nodes = element.children,
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryTagRow(nodes: List<DictionaryNode>, modifier: Modifier = Modifier) {
    val tags = remember(nodes) {
        nodes.map { dictionaryNodePlainText(it).trim() }.filter { it.isNotBlank() }
    }
    if (tags.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        tags.forEach { tag ->
            DictionaryTagChip(tag = tag)
        }
    }
}

@Composable
private fun DictionaryBacklinkRow(element: DictionaryElementNode, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        element.children.forEach { child ->
            if (child is DictionaryElementNode && child.tag == "a") {
                val linkText = dictionaryNodesPlainText(child.children).trim()
                if (linkText.isNotBlank()) {
                    Text(
                        text = linkText,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            runCatching { uriHandler.openUri(child.href.orEmpty()) }
                        }
                    )
                }
            } else {
                val text = dictionaryNodePlainText(child)
                if (text.isNotBlank()) {
                    Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DictionaryInlineText(
    nodes: List<DictionaryNode>,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 20.sp,
    italic: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    fillMaxWidth: Boolean = true
) {
    val text = remember(nodes, color, italic) {
        dictionaryAnnotatedText(nodes, color, italic)
    }
    if (text.text.isBlank()) return

    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        textAlign = textAlign,
        modifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier
    )
}

@Composable
private fun DictionaryHtmlText(
    definition: String,
    color: Color
) {
    val linkColor = MaterialTheme.colorScheme.primary
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                includeFontPadding = false
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.12f)
                setTextSize(14f)
            }
        },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setLinkTextColor(linkColor.toArgb())
            textView.text = dictionaryDefinitionDisplayText(definition)
        }
    )
}

private sealed class DictionaryNode

private data class DictionaryTextNode(val text: String) : DictionaryNode()

private data class DictionaryElementNode(
    val tag: String,
    val dataContent: String?,
    val dataCategory: String?,
    val title: String?,
    val href: String?,
    val open: Boolean,
    val children: List<DictionaryNode>
) : DictionaryNode()

private fun parseStructuredDefinition(definition: String): List<DictionaryNode>? {
    val trimmed = definition.trim()
    if (!trimmed.isStructuredJsonCandidate()) return null

    return try {
        val root = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        parseDictionaryNodes(root).takeIf { dictionaryNodesPlainText(it).isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

private fun parseDictionaryNodes(node: Any?): List<DictionaryNode> {
    return when (node) {
        null, JSONObject.NULL -> emptyList()
        is String -> listOf(DictionaryTextNode(node))
        is JSONArray -> buildList {
            for (i in 0 until node.length()) {
                addAll(parseDictionaryNodes(node.opt(i)))
            }
        }
        is JSONObject -> {
            val type = node.optString("type")
            if (type == "structured-content") {
                parseDictionaryNodes(node.opt("content"))
            } else {
                val data = node.optJSONObject("data")
                listOf(
                    DictionaryElementNode(
                        tag = node.optString("tag"),
                        dataContent = data?.optString("content")?.takeIf { it.isNotBlank() },
                        dataCategory = data?.optString("category")?.takeIf { it.isNotBlank() },
                        title = node.optString("title").takeIf { it.isNotBlank() },
                        href = node.optString("href").takeIf { it.isNotBlank() },
                        open = when {
                            !node.has("open") -> false
                            node.opt("open") is Boolean -> node.optBoolean("open", false)
                            else -> true
                        },
                        children = parseDictionaryNodes(node.opt("content"))
                    )
                )
            }
        }
        else -> listOf(DictionaryTextNode(node.toString()))
    }
}

private fun dictionaryEntryTags(entry: DictEntry): List<String> {
    return "${entry.definitionTags} ${entry.termTags}"
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun dictionaryAnnotatedText(
    nodes: List<DictionaryNode>,
    color: Color,
    italic: Boolean
): AnnotatedString {
    return buildAnnotatedString {
        nodes.forEach { node ->
            appendDictionaryNode(node, color = color, bold = false, italic = italic, link = false)
        }
    }
}

private fun AnnotatedString.Builder.appendDictionaryNode(
    node: DictionaryNode,
    color: Color,
    bold: Boolean,
    italic: Boolean,
    link: Boolean
) {
    when (node) {
        is DictionaryTextNode -> {
            val style = SpanStyle(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (link) TextDecoration.Underline else null
            )
            pushStyle(style)
            append(node.text.replace('\u00A0', ' '))
            pop()
        }
        is DictionaryElementNode -> {
            val nextBold = bold ||
                node.tag == "b" ||
                node.tag == "strong" ||
                node.dataContent == "bold-text"
            val nextItalic = italic || node.tag == "i" || node.tag == "em"
            val nextLink = link || node.tag == "a"
            node.children.forEach { child ->
                appendDictionaryNode(
                    node = child,
                    color = color,
                    bold = nextBold,
                    italic = nextItalic,
                    link = nextLink
                )
            }
        }
    }
}

private fun DictionaryNode.isStandaloneDictionaryBlock(): Boolean {
    return this is DictionaryElementNode && (
        tag == "details" ||
            tag == "ol" ||
            tag == "ul" ||
            tag == "li" ||
            dataContent == "preamble" ||
            dataContent == "tags" ||
            dataContent == "example-sentence" ||
            dataContent == "extra-info" ||
            dataContent == "backlink" ||
            dataContent == "synonyms"
        )
}

private fun DictionaryNode.shouldHideInCompactDictionaryEntry(): Boolean {
    return this is DictionaryElementNode && (
        dataContent == "example-sentence" ||
            dataContent == "extra-info" ||
            dataContent?.startsWith("details-entry-Etymology") == true ||
            dataContent?.startsWith("details-entry-examples") == true
        )
}

private fun DictionaryElementNode.isGrammarDetails(): Boolean {
    return tag == "details" && dataContent?.startsWith("details-entry-Grammar") == true
}

private fun DictionaryNode.isSenseDetailBlock(): Boolean {
    return this is DictionaryElementNode && (
        tag == "details" ||
            tag == "ol" ||
            tag == "ul" ||
            dataContent == "example-sentence" ||
            dataContent == "extra-info" ||
            dataContent == "backlink" ||
            dataContent == "synonyms"
        )
}

private fun DictionaryElementNode.singleChildElement(tagName: String): DictionaryElementNode? {
    return (children.singleOrNull() as? DictionaryElementNode)
        ?.takeIf { it.tag == tagName }
}

private fun dictionaryNodesPlainText(nodes: List<DictionaryNode>): String {
    return buildString {
        nodes.forEach { appendDictionaryPlainText(it) }
    }.replace('\u00A0', ' ')
}

private fun dictionaryNodePlainText(node: DictionaryNode): String {
    return buildString { appendDictionaryPlainText(node) }.replace('\u00A0', ' ')
}

private fun StringBuilder.appendDictionaryPlainText(node: DictionaryNode) {
    when (node) {
        is DictionaryTextNode -> append(node.text)
        is DictionaryElementNode -> {
            if (node.shouldHideInCompactDictionaryEntry()) return
            val summary = node.children.firstOrNull {
                it is DictionaryElementNode && it.tag == "summary"
            }
            val children = if (node.isGrammarDetails()) {
                append("Grammar: ")
                node.children.filterNot { it === summary }
            } else {
                node.children
            }
            val isBlock = node.tag in setOf("div", "p", "li", "ol", "ul", "details", "summary")
            if (isBlock && isNotEmpty() && last() != '\n') append('\n')
            children.forEach { appendDictionaryPlainText(it) }
            if (isBlock && isNotEmpty() && last() != '\n') append('\n')
        }
    }
}

private fun String.isStructuredJsonCandidate(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("[") || trimmed.startsWith("{")
}

private fun splitJapaneseMorae(reading: String): List<String> {
    val smallKana = setOf('ゃ', 'ゅ', 'ょ', 'ャ', 'ュ', 'ョ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ')
    val list = mutableListOf<String>()
    var i = 0
    while (i < reading.length) {
        if (i + 1 < reading.length && reading[i + 1] in smallKana) {
            list.add(reading.substring(i, i + 2))
            i += 2
        } else {
            list.add(reading[i].toString())
            i++
        }
    }
    return list
}

private fun dictionarySourcePlainText(processedDefinitions: List<String>): String {
    return processedDefinitions
        .asSequence()
        .map { dictionaryDefinitionPlainText(it).trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

private fun firstDictionaryDefinitionLine(sourceText: String): String {
    return sourceText
        .lineSequence()
        .map { cleanDictionaryDefinitionLine(it) }
        .firstOrNull { it.isNotBlank() }
        ?.takeIf { it.isNotBlank() }
        ?: "Definition"
}

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

private fun dictionaryDefinitionPlainText(processedDefinition: String): String {
    val structuredNodes = parseStructuredDefinition(processedDefinition)
    return if (structuredNodes != null) {
        dictionaryNodesPlainText(structuredNodes)
    } else if (processedDefinition.contains("<")) {
        Html.fromHtml(processedDefinition, Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        processedDefinition
    }.replace('\u00A0', ' ')
}

private fun dictionaryDefinitionDisplayText(processedDefinition: String): CharSequence {
    return if (processedDefinition.contains("<")) {
        Html.fromHtml(processedDefinition, Html.FROM_HTML_MODE_LEGACY)
    } else {
        processedDefinition
    }
}

private fun cleanDictionaryDefinitionLine(line: String): String {
    return line
        .trim()
        .trimStart('•', '・', '-')
        .trim()
}

fun convertStructuredToHtml(json: String): String {
    return try {
        val root = if (json.trim().startsWith("[")) JSONArray(json) else JSONObject(json)
        val sb = StringBuilder()
        parseStructuredNode(root, sb)
        sb.toString()
    } catch (e: Exception) {
        json
    }
}

private fun parseStructuredNode(node: Any, sb: StringBuilder) {
    when (node) {
        is String -> sb.append(node.replace("\n", "<br>"))
        is JSONArray -> {
            for (i in 0 until node.length()) {
                parseStructuredNode(node.get(i), sb)
            }
        }
        is JSONObject -> {
            val type = node.optString("type")
            if (type == "structured-content") {
                parseStructuredNode(node.opt("content") ?: "", sb)
                return
            }

            val tag = node.optString("tag", "")
            val content = node.opt("content")

            if (tag.isNotEmpty()) {
                sb.append("<$tag")
                val href = node.optString("href")
                if (href.isNotEmpty()) sb.append(" href=\"$href\"")
                sb.append(">")
                if (content != null) parseStructuredNode(content, sb)
                sb.append("</$tag>")
            } else if (content != null) {
                parseStructuredNode(content, sb)
            }
        }
    }
}
