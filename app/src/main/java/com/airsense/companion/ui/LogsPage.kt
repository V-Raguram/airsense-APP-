package com.airsense.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.companion.AppState
import com.airsense.companion.data.*

@Composable
fun LogsPage(state: AppState, onRefresh: () -> Unit, onDownload: (RemoteLog) -> Unit,
    onOpen: (LocalLog) -> Unit, onExport: (LocalLog) -> Unit, onImport: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var localOnly by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Your measurement archive", "Every reading, kept.", "Download device logs and explore them anywhere.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onRefresh, enabled = !state.loadingLogs) { Text(if (state.loadingLogs) "Loading…" else "Refresh Pi logs") }
                OutlinedButton(onImport) { Text("Import CSV") }
            }
        }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Filter by date or filename") }, singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!localOnly, { localOnly = false }, label = { Text("On the Pi (${state.remoteLogs.size})") })
                FilterChip(localOnly, { localOnly = true }, label = { Text("On this phone (${state.localLogs.size})") })
            }
        }
        if (state.downloading != null) item {
            PanelCard {
                Text("Saving ${state.downloading}", fontSize = 13.sp)
                LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth(), color = Mint)
                Text("${(state.downloadProgress * 100).toInt()}% · keeping complete CSV rows", color = Muted, fontSize = 11.sp)
            }
        }
        if (localOnly) {
            val logs = state.localLogs.filter { it.name.contains(query, true) }
            if (logs.isEmpty()) item { EmptyPanel("No saved logs", "Download a log from your Pi, import a CSV, or explore sample data from Settings.") }
            items(logs, key = { it.file.absolutePath }) { log ->
                PanelCard {
                    Text(log.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text("${humanBytes(log.bytes)} · ${if (log.file.parentFile?.name == "sample") "Generated sample" else "Available offline"}", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onOpen(log) }) { Text("Analyse") }
                        OutlinedButton({ onExport(log) }) { Text("Export CSV") }
                    }
                }
            }
        } else {
            val logs = state.remoteLogs.filter { it.name.contains(query, true) }
            if (logs.isEmpty() && !state.loadingLogs) item {
                EmptyPanel("Your Pi archive", "Connect to the Pi hotspot, then refresh to list its existing daily CSV files. Saved logs remain available on this phone.")
            }
            items(logs, key = { it.name }) { log ->
                PanelCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(log.name.removePrefix("airsense_log_").substringBefore(".csv"), style = MaterialTheme.typography.titleMedium)
                        StatusPill(if (log.name.substringAfter(".csv").isNotEmpty()) "ROTATED" else "CSV", Muted)
                    }
                    Text("${humanBytes(log.bytes)} · ${log.name}", color = Muted, fontSize = 11.sp)
                    Button({ onDownload(log) }, enabled = state.downloading == null) { Text("Download & analyse") }
                }
            }
            if (state.loadingLogs) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mint) } }
        }
    }
}
