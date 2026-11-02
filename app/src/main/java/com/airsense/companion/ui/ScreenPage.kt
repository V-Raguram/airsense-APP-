package com.airsense.companion.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.companion.AppState
import com.airsense.companion.data.AnsiParser
import com.airsense.companion.data.TerminalPane
import java.text.DateFormat
import java.util.Date

@Composable
fun ScreenPage(state: AppState, onFindPanes: () -> Unit, onSelect: (TerminalPane) -> Unit, onRefresh: () -> Unit, onAuto: (Boolean) -> Unit) {
    var fontSize by remember { mutableFloatStateOf(10f) }
    val terminal = remember(state.terminal) {
        buildAnnotatedString {
            AnsiParser.parse(state.terminal).forEach { run ->
                withStyle(SpanStyle(color = run.foreground?.let(::Color) ?: Color(0xFFE8F0F1),
                    background = run.background?.let(::Color) ?: Color.Transparent,
                    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal)) { append(run.text) }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("A window into your device", "The Pi, at a glance.", "View the existing terminal dashboard while it keeps running.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onFindPanes, enabled = !state.screenBusy) { Text(if (state.screenBusy) "Reading…" else "Find dashboard") }
                OutlinedButton(onRefresh, enabled = state.selectedPane != null && !state.screenBusy) { Text("Refresh") }
            }
        }
        if (state.panes.isNotEmpty()) item {
            Text("Choose the pane running Python", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.panes.forEach { pane ->
                    FilterChip(state.selectedPane == pane, { onSelect(pane) }, label = { Text("${pane.label} · ${pane.command}", fontSize = 12.sp) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-refresh every 3 seconds", color = Muted, fontSize = 12.sp)
                Switch(state.screenAutoRefresh, onAuto)
            }
            Text(state.screenStatus, color = if (state.terminal.isEmpty()) Muted else Mint, fontSize = 11.sp)
        }
        if (state.terminal.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    SelectionContainer {
                        Text(terminal, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp, lineHeight = (fontSize * 1.2).sp, softWrap = false)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Text size", color = Muted, fontSize = 12.sp)
                    Text("${fontSize.toInt()} sp", color = Mint, fontSize = 12.sp)
                }
                Slider(fontSize, { fontSize = it }, valueRange = 6f..20f)
                Text("Last captured ${DateFormat.getTimeInstance().format(Date(state.terminalUpdated))}", color = Muted, fontSize = 11.sp)
            }
        } else item { EmptyPanel("Read-only screen view", "Find your existing tmux session, then select its dashboard pane. Terminal text and colours are reproduced here. Rotate the phone for a wider view.") }
        item { Text("This view sends no keyboard input and does not resize or interrupt the Pi display. It reproduces terminal content rather than a physical-screen screenshot.", color = Muted, fontSize = 11.sp) }
    }
}
