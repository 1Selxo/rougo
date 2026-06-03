package com.selxo.rougo

import android.text.Html
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun JapaneseClickableSubtitle(text: String, onWordClicked: (String) -> Unit) {
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
            .pointerInput(text) {
                detectTapGestures { pos ->
                    layoutResult?.let { layout ->
                        val offset = layout.getOffsetForPosition(pos)
                        if (offset < text.length) {
                            val endIndex = minOf(text.length, offset + 8)
                            onWordClicked(text.substring(offset, endIndex))
                        }
                    }
                }
            },
        onTextLayout = { layoutResult = it }
    )
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

    LaunchedEffect(searchQuery) {
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Dictionary") },
                modifier = Modifier.fillMaxWidth(),
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
                                    DictGroupCard(groupEntries)
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
fun DictGroupCard(entries: List<DictEntry>) {
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
                val processedDefinitions = dictEntries.map { entry ->
                    if (entry.definition.trim().startsWith("[") || entry.definition.trim().startsWith("{")) {
                        convertStructuredToHtml(entry.definition)
                    } else {
                        entry.definition
                    }
                }
                DictionaryEntrySection(
                    dictName = dictName,
                    processedDefinitions = processedDefinitions,
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
    processedDefinitions: List<String>,
    chipContainer: Color
) {
    var expanded by remember(dictName, processedDefinitions) { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val sourceText = remember(processedDefinitions) { dictionarySourcePlainText(processedDefinitions) }
    val preview = remember(sourceText) { firstDictionaryDefinitionLine(sourceText) }
    val expandable = remember(sourceText, preview) { isExpandableDictionarySource(sourceText, preview) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = expandable) { expanded = !expanded }
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
            if (expanded) {
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
            if (expandable) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse dictionary source" else "Expand dictionary source",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (expanded && sourceText.isNotBlank()) {
            Text(
                sourceText,
                color = colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp)
            )
        }
    }
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
    return if (processedDefinition.contains("<")) {
        Html.fromHtml(processedDefinition, Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        processedDefinition
    }.replace('\u00A0', ' ')
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
