package com.airsense.companion.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.companion.AppState
import com.airsense.companion.data.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

@Composable
fun AnalysisPage(state: AppState, onAnalyse: (Sensor, LocalDateTime?, LocalDateTime?) -> Unit,
    onChooseLog: () -> Unit, onExport: (LocalLog) -> Unit) {
    var from by remember(state.selectedLog) { mutableStateOf("") }
    var until by remember(state.selectedLog) { mutableStateOf("") }
    var rangeError by remember { mutableStateOf<String?>(null) }
    var showRange by remember { mutableStateOf(false) }
    fun apply(sensor: Sensor) {
        try {
            val start = from.takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it.trim(), logDateTime) }
            val end = until.takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it.trim(), logDateTime) }
            require(start == null || end == null || start <= end)
            rangeError = null
            onAnalyse(sensor, start, end)
        } catch (_: Exception) { rangeError = "Use YYYY-MM-DD HH:mm:ss, with the start before the end." }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageTitle("Patterns over time", "Make sense of the data.", "Explore one parameter at a time, with its own units.") }
        if (state.selectedLog == null) {
            item { EmptyPanel("Choose a log to begin", "Download a log from the Pi or open one saved on your phone.", "Open logs", onChooseLog) }
        } else {
            item {
                PanelCard {
                    Text(state.selectedLog.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("${humanBytes(state.selectedLog.bytes)} · ${if (state.selectedLog.file.parentFile?.name == "sample") "Generated sample" else "Saved on this phone"}", color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onChooseLog) { Text("Change log") }
                        TextButton({ onExport(state.selectedLog) }) { Text("Export CSV") }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sensor.entries.forEach { sensor ->
                        FilterChip(state.sensor == sensor, { apply(sensor) }, label = { Text(sensor.title) })
                    }
                }
            }
            item {
                TextButton({ showRange = !showRange }) { Text(if (showRange) "Hide time filter" else "Filter time range") }
                if (showRange) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(from, { from = it }, label = { Text("From · YYYY-MM-DD HH:mm:ss") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(until, { until = it }, label = { Text("To · YYYY-MM-DD HH:mm:ss") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Times use the device's recorded clock. Leave either field blank for the full range.", color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ apply(state.sensor) }) { Text("Apply") }
                        TextButton({ from = ""; until = ""; rangeError = null; onAnalyse(state.sensor, null, null) }) { Text("Reset") }
                    }
                    rangeError?.let { Text(it, color = Coral, fontSize = 12.sp) }
                }
            }
            if (state.analysing) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mint) } }
            state.analysisError?.let { error -> item { EmptyPanel("Cannot analyse this selection", error) } }
            state.analysis?.let { analysis ->
                if (analysis.count == 0L) item { EmptyPanel("No numeric readings in this range", "Missing values are kept as gaps. Try another sensor or time range.") }
                else {
                    item { ChartCard(analysis) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Minimum", state.sensor.display(analysis.minimum), state.sensor.unit, Modifier.weight(1f))
                            StatCard("Average", state.sensor.display(analysis.average), state.sensor.unit, Modifier.weight(1f))
                            StatCard("Maximum", state.sensor.display(analysis.maximum), state.sensor.unit, Modifier.weight(1f))
                        }
                    }
                }
                item {
                    Text("${analysis.count} numeric readings · ${analysis.missing} missing · ${analysis.malformedRows} invalid rows", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Statistics use every valid reading in the selected range. Large graphs retain bucket extrema; buckets containing missing data appear as gaps.", color = Muted, fontSize = 11.sp)
                }
                if (analysis.preview.isNotEmpty()) item {
                    Text("CSV preview · first ${analysis.preview.size} matching rows", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.horizontalScroll(rememberScrollState()).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Row { analysis.header.forEach { Text(it, Modifier.width(115.dp).padding(5.dp), color = Mint, fontSize = 10.sp) } }
                        analysis.preview.forEach { row ->
                            HorizontalDivider(color = Border)
                            Row { row.forEach { Text(it, Modifier.width(115.dp).padding(5.dp), color = Muted, fontSize = 11.sp) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Text(unit, color = Muted, fontSize = 10.sp)
    }
}

private class Viewport {
    var start by mutableDoubleStateOf(0.0)
    var width by mutableDoubleStateOf(1.0)
    var selected by mutableStateOf<PlotPoint?>(null)
    fun reset() { start = 0.0; width = 1.0; selected = null }
}

@Composable
private fun ChartCard(analysis: Analysis) {
    val viewport = remember(analysis) { Viewport() }
    var thresholds by remember { mutableStateOf(false) }
    val points = analysis.points
    val startEpoch = analysis.first!!.toEpochSecond(ZoneOffset.UTC).toDouble()
    val fullSpan = max(1.0, analysis.last!!.toEpochSecond(ZoneOffset.UTC) - startEpoch)
    val visibleStart = startEpoch + viewport.start * fullSpan
    val visibleSpan = fullSpan * viewport.width
    val minimum = minOf(0.0, analysis.minimum ?: 0.0)
    val maximum = max(minimum + 1, (analysis.maximum ?: 1.0) * 1.12)
    val span = maximum - minimum
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    PanelCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${analysis.sensor.title} · ${analysis.sensor.unit}", fontWeight = FontWeight.Medium)
            TextButton({ viewport.reset() }) { Text("Reset view", fontSize = 11.sp) }
        }
        viewport.selected?.let { point ->
            Text("${point.time.format(timeFormat)}   ${analysis.sensor.display(point.value)} ${analysis.sensor.unit}", color = Mint, fontSize = 12.sp)
        } ?: Text("Pinch to zoom · drag to pan · tap to inspect", color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().height(230.dp)) {
            Column(Modifier.width(43.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                (0..4).forEach { index -> Text(analysis.sensor.display(maximum - span * index / 4), color = Muted, fontSize = 9.sp) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()
                .pointerInput(analysis) {
                    detectTapGestures { offset ->
                        val target = startEpoch + viewport.start * fullSpan + (offset.x / size.width).coerceIn(0f, 1f) * fullSpan * viewport.width
                        viewport.selected = points.minByOrNull { abs(it.time.toEpochSecond(ZoneOffset.UTC) - target) }
                    }
                }
                .pointerInput(analysis) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val previous = viewport.width
                        val next = (previous / zoom).coerceIn(.005, 1.0)
                        val anchor = (centroid.x / size.width).toDouble().coerceIn(0.0, 1.0)
                        viewport.start = (viewport.start + (previous - next) * anchor - pan.x / size.width * previous).coerceIn(0.0, 1.0 - next)
                        viewport.width = next
                    }
                }) {
                clipRect {
                    for (n in 0..4) {
                        val y = size.height * n / 4
                        drawLine(Border, Offset(0f, y), Offset(size.width, y), 1f)
                        val x = size.width * n / 4
                        drawLine(Border.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height), 1f)
                    }
                    if (thresholds) {
                        val limits = if (analysis.sensor == Sensor.LUX) listOf(250.0, 500.0) else listOf(analysis.sensor.safe, analysis.sensor.danger)
                        limits.forEach { limit ->
                            val y = ((1 - (limit - minimum) / span) * size.height).toFloat()
                            drawLine(Amber.copy(alpha = .65f), Offset(0f, y), Offset(size.width, y), 1.3f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                        }
                    }
                    val line = Path()
                    var drawing = false
                    var count = 0
                    var lastOffset: Offset? = null
                    points.forEach { point ->
                        val epoch = point.time.toEpochSecond(ZoneOffset.UTC).toDouble()
                        if (point.value == null) drawing = false
                        else {
                            val x = ((epoch - visibleStart) / visibleSpan * size.width).toFloat()
                            val y = ((1 - (point.value - minimum) / span) * size.height).toFloat()
                            if (!drawing) line.moveTo(x, y) else line.lineTo(x, y)
                            drawing = true
                            if (x in 0f..size.width) { count++; lastOffset = Offset(x, y) }
                        }
                    }
                    drawPath(line, Mint, style = Stroke(2.3f, cap = StrokeCap.Round))
                    if (count == 1) lastOffset?.let { drawCircle(Mint, 4f, it) }
                    viewport.selected?.let { point ->
                        val x = ((point.time.toEpochSecond(ZoneOffset.UTC) - visibleStart) / visibleSpan * size.width).toFloat()
                        drawLine(Muted, Offset(x, 0f), Offset(x, size.height), 1f)
                        point.value?.let { value -> drawCircle(Mint, 5f, Offset(x, ((1 - (value - minimum) / span) * size.height).toFloat())) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 43.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LocalDateTime.ofEpochSecond(visibleStart.toLong(), 0, ZoneOffset.UTC).format(timeFormat), color = Muted, fontSize = 9.sp)
            Text(LocalDateTime.ofEpochSecond((visibleStart + visibleSpan).toLong(), 0, ZoneOffset.UTC).format(timeFormat), color = Muted, fontSize = 9.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(thresholds, { thresholds = it })
            Text(if (analysis.sensor == Sensor.LUX) "Show configured normal range" else "Show configured thresholds", fontSize = 11.sp, color = Muted)
        }
        Text("${analysis.first!!.toLocalDate()} → ${analysis.last!!.toLocalDate()}", color = Muted, fontSize = 10.sp)
    }
}
