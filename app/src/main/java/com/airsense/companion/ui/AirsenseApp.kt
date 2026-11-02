package com.airsense.companion.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airsense.companion.*
import com.airsense.companion.data.LocalLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirsenseApp(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var export by remember { mutableStateOf<LocalLog?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) export?.let { model.exportLog(it, uri) }
        export = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importLog)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.onForeground(true)
            if (event == Lifecycle.Event.ON_STOP) model.onForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onForeground(true)
        onDispose { owner.lifecycle.removeObserver(observer); model.onForeground(false) }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { message -> snackbar.showSnackbar(message); model.clearNotice() }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BrandMark()
                    Text("airsense", fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp, fontSize = 23.sp)
                }
            }, actions = {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Wi-Fi", color = Mint) }
            })
        },
        bottomBar = {
            NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                Page.entries.forEach { page ->
                    val selected = state.page == page
                    NavigationBarItem(selected, onClick = { model.setPage(page) },
                        icon = { PageIcon(page, if (selected) Mint else Muted) },
                        label = { Text(page.title, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Mint.copy(alpha = .12f), selectedTextColor = Mint, unselectedTextColor = Muted))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (state.demo) {
                Surface(color = Amber.copy(alpha = .12f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SAMPLE MODE · generated data", color = Amber, fontSize = 11.sp)
                        TextButton(model::exitDemo, contentPadding = PaddingValues(0.dp)) { Text("Exit", color = Amber) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.page) {
                    Page.LIVE -> LivePage(state, model::connect, model::disconnect)
                    Page.LOGS -> LogsPage(state, model::refreshLogs, model::download, model::selectLog,
                        onExport = { log -> export = log; exportLauncher.launch(log.name) },
                        onImport = { importLauncher.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) })
                    Page.ANALYSIS -> AnalysisPage(state, model::analyse, { model.setPage(Page.LOGS) },
                        onExport = { log -> export = log; exportLauncher.launch(log.name) })
                    Page.SCREEN -> ScreenPage(state, model::discoverPanes, model::selectPane, model::captureScreen, model::autoRefresh)
                    Page.SETTINGS -> SettingsPage(state, model::saveSettings, model::testSsh, model::startDemo, model::forgetCredentials, model::notify)
                }
            }
        }
    }
    state.hostChallenge?.let { host ->
        AlertDialog(onDismissRequest = model::rejectHost,
            title = { Text(if (host.changed) "Pi identity has changed" else "First time connecting to this Pi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${host.host}:${host.port}")
                    Text(host.fingerprint, style = MaterialTheme.typography.bodySmall, color = Mint)
                    Text(if (host.changed) "The key differs from the one saved on this phone. Only replace it if you have verified the Pi was reinstalled or its SSH key changed."
                        else "Tap Trust Pi to continue. This phone will remember it and warn you if it ever changes.")
                }
            },
            confirmButton = { TextButton(model::trustHost) { Text(if (host.changed) "Replace trusted key" else "Trust Pi") } },
            dismissButton = { TextButton(model::rejectHost) { Text("Cancel") } })
    }
}
