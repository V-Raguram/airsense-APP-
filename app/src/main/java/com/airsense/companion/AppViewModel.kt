package com.airsense.companion

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airsense.companion.data.*
import com.airsense.companion.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale

enum class Page(val title: String) { LIVE("Live"), LOGS("Logs"), ANALYSIS("Analyse"), SCREEN("Screen"), SETTINGS("Settings") }

data class AppState(
    val settings: DeviceSettings = DeviceSettings(),
    val page: Page = Page.SETTINGS,
    val live: LiveReading = LiveReading(),
    val mqttStatus: String = "Disconnected",
    val mqttConnected: Boolean = false,
    val invalidPackets: Long = 0,
    val remoteLogs: List<RemoteLog> = emptyList(),
    val localLogs: List<LocalLog> = emptyList(),
    val sshStatus: String = "Not connected",
    val loadingLogs: Boolean = false,
    val downloading: String? = null,
    val downloadProgress: Float = 0f,
    val selectedLog: LocalLog? = null,
    val sensor: Sensor = Sensor.NOX,
    val analysis: Analysis? = null,
    val analysing: Boolean = false,
    val analysisError: String? = null,
    val panes: List<TerminalPane> = emptyList(),
    val selectedPane: TerminalPane? = null,
    val terminal: String = "",
    val terminalUpdated: Long = 0,
    val screenStatus: String = "Choose the running dashboard pane.",
    val screenAutoRefresh: Boolean = true,
    val screenBusy: Boolean = false,
    val hostChallenge: HostChallenge? = null,
    val notice: String? = null,
    val demo: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val network = LocalNetwork(application)
    private val pi = PiClient(store, network)
    private val root = File(application.filesDir, "logs").apply { mkdirs() }
    private val initialSettings = runCatching { store.load() }
    private val _state = MutableStateFlow(AppState(
        settings = initialSettings.getOrDefault(DeviceSettings()),
        page = if (initialSettings.getOrNull()?.configured == true) Page.LIVE else Page.SETTINGS,
        notice = if (initialSettings.isFailure) "Saved credentials could not be decrypted. Enter the connection settings again." else null,
    ))
    val state = _state.asStateFlow()
    private var foreground = false
    private var wantConnection = true
    private var mqttJob: Job? = null
    private var subscriber: MqttSubscriber? = null
    private var screenJob: Job? = null
    private var analysisJob: Job? = null
    private var demoJob: Job? = null
    private var retryAfterTrust: (() -> Unit)? = null

    init { refreshLocalLogs() }

    fun setPage(page: Page) {
        _state.update { it.copy(page = page) }
        updateScreenPolling()
        if (page == Page.SCREEN) autoFindDashboard()
    }
    /** Opening the Screen tab finds and shows the dashboard without extra taps. */
    private fun autoFindDashboard() {
        val s = _state.value
        if (s.demo || !s.settings.configured || s.selectedPane != null || s.panes.isNotEmpty()) return
        discoverPanes()
    }
    private fun likelyDashboard(panes: List<TerminalPane>): TerminalPane? =
        panes.firstOrNull { it.command.contains("python", true) }
            ?: panes.firstOrNull { it.label.startsWith("main:") }
            ?: panes.singleOrNull()
    fun clearNotice() { _state.update { it.copy(notice = null) } }
    fun notify(message: String) { _state.update { it.copy(notice = message) } }

    fun onForeground(active: Boolean) {
        foreground = active
        if (active && _state.value.demo) startDemoTicks()
        else if (active && wantConnection && _state.value.settings.configured) startMqtt()
        else if (!active) {
            stopMqtt()
            demoJob?.cancel()
        }
        updateScreenPolling()
    }

    fun saveSettings(settings: DeviceSettings) {
        settings.validate()?.let { notify(it); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(settings) }
                stopMqtt()
                screenJob?.cancel()
                demoJob?.cancel()
                wantConnection = true
                _state.update { AppState(settings = settings.copy(configured = true), page = Page.LIVE, localLogs = it.localLogs,
                    notice = "Connection settings saved on this phone.") }
                if (foreground) startMqtt()
                testSsh()
            } catch (error: Exception) { report(error) }
        }
    }

    fun connect() {
        if (!_state.value.settings.configured) { setPage(Page.SETTINGS); return }
        demoJob?.cancel()
        stopMqtt()
        _state.update { it.copy(demo = false, live = LiveReading(), invalidPackets = 0) }
        wantConnection = true
        startMqtt()
    }
    fun disconnect() {
        wantConnection = false
        stopMqtt()
        screenJob?.cancel()
        _state.update { it.copy(mqttStatus = "Disconnected", mqttConnected = false, screenStatus = "Disconnected") }
    }

    private fun stopMqtt() {
        mqttJob?.cancel()
        subscriber?.close()
        subscriber = null
        mqttJob = null
        _state.update { it.copy(mqttConnected = false, mqttStatus = if (foreground) "Disconnected" else "Paused while app is in background") }
    }

    private fun startMqtt() {
        if (mqttJob?.isActive == true || !foreground || !wantConnection || _state.value.demo) return
        val settings = _state.value.settings
        mqttJob = viewModelScope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive) {
                val client = MqttSubscriber(network.socketFactory())
                subscriber = client
                _state.update { it.copy(mqttStatus = "Connecting to ${settings.host}…", mqttConnected = false) }
                val heartbeat = launch {
                    while (isActive) { delay(8000); runCatching { client.ping() }.onFailure { client.close() } }
                }
                try {
                    client.run(settings,
                        onConnected = {
                            if (isActive && subscriber === client) {
                                backoff = 1000
                                _state.update { it.copy(mqttConnected = true, mqttStatus = "Waiting for sensor data") }
                            }
                        },
                        onReading = { reading -> if (isActive && subscriber === client) _state.update { it.copy(live = reading, mqttStatus = "Receiving live data") } },
                        onInvalid = { if (isActive && subscriber === client) _state.update { it.copy(invalidPackets = it.invalidPackets + 1) } })
                } catch (error: Exception) {
                    if (isActive) _state.update { it.copy(mqttConnected = false,
                        mqttStatus = "${friendly(error)} · retrying in ${backoff / 1000}s") }
                } finally { heartbeat.cancel(); client.close() }
                if (isActive) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15000)
            }
        }
    }

    private fun report(error: Exception, retry: (() -> Unit)? = null) {
        if (error is CancellationException) throw error
        if (error is HostVerificationRequired) {
            retryAfterTrust = retry
            _state.update { it.copy(hostChallenge = error.challenge) }
        } else notify(friendly(error))
    }
    private fun friendly(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("Auth fail", true) || text.contains("exhausted available authentication", true) -> "SSH authentication failed. Check your username, password, or private key."
            text.contains("No such file", true) -> "The Pi log folder was not found. Check the path in Settings."
            text.contains("refused", true) -> "Connection refused. Check the Pi address and that SSH/MQTT is already running."
            text.contains("timeout", true) || text.contains("timed out", true) -> "Connection timed out. Connect the phone to the Pi hotspot and retry."
            else -> text.take(240).ifBlank { error.javaClass.simpleName }
        }
    }
    fun trustHost() {
        val challenge = _state.value.hostChallenge ?: return
        try { store.trust(challenge) } catch (error: Exception) { report(error); return }
        _state.update { it.copy(hostChallenge = null) }
        retryAfterTrust?.invoke()
        retryAfterTrust = null
    }
    fun rejectHost() { retryAfterTrust = null; _state.update { it.copy(hostChallenge = null) } }

    fun testSsh() {
        val settings = _state.value.settings
        if (!settings.configured) { notify("Save your connection settings first."); return }
        if (_state.value.demo) { notify("Exit sample mode from Settings to connect to your Pi."); return }
        _state.update { it.copy(sshStatus = "Checking SSH and log folder…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { pi.test(settings) }
                _state.update { it.copy(sshStatus = "SSH connected · log folder available", notice = "SSH and SFTP are ready.") }
            } catch (error: Exception) {
                _state.update { it.copy(sshStatus = "SSH check failed") }
                report(error, ::testSsh)
            }
        }
    }

    fun refreshLogs() {
        refreshLocalLogs()
        if (_state.value.demo) { notify("Sample mode contains one local example log."); return }
        if (!_state.value.settings.configured) { setPage(Page.SETTINGS); notify("Save your Pi connection details first."); return }
        if (_state.value.loadingLogs) return
        val settings = _state.value.settings
        _state.update { it.copy(loadingLogs = true) }
        viewModelScope.launch {
            try {
                val logs = withContext(Dispatchers.IO) { pi.logs(settings) }
                _state.update { it.copy(remoteLogs = logs, sshStatus = "SSH connected", loadingLogs = false) }
            } catch (error: Exception) { _state.update { it.copy(loadingLogs = false) }; report(error, ::refreshLogs) }
        }
    }

    private fun deviceFolder(settings: DeviceSettings): File {
        val identity = "${settings.host}:${settings.sshPort}/${settings.username}/${settings.logDirectory}"
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return File(root, hash)
    }
    fun download(log: RemoteLog) {
        if (_state.value.downloading != null) return
        val settings = _state.value.settings
        _state.update { it.copy(downloading = log.name, downloadProgress = 0f) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    pi.download(settings, log, deviceFolder(settings)) { current, total ->
                        _state.update { it.copy(downloadProgress = if (total == 0L) 1f else current.toFloat() / total) }
                    }
                }
                refreshLocalLogs()
                selectLog(LocalLog(file))
                notify("${log.name} saved on this phone. Use Export to save a copy in Downloads.")
            } catch (error: Exception) { report(error) { download(log) } }
            finally { _state.update { it.copy(downloading = null) } }
        }
    }
    private fun refreshLocalLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = root.walkTopDown().filter { it.isFile && logNameAllowed(it.name) }.map { LocalLog(it) }.toList()
                .sortedByDescending { it.file.lastModified() }
            _state.update { it.copy(localLogs = logs) }
        }
    }
    fun importLog(uri: Uri) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(root, "imports").apply { mkdirs() }
                    val date = java.time.LocalDate.now().toString()
                    val output = File(directory, "airsense_log_$date.csv.${System.currentTimeMillis()}")
                    try {
                        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                            output.outputStream().use { destination ->
                                val bytes = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    val read = input.read(bytes)
                                    if (read < 0) break
                                    count += read
                                    require(count <= PiClient.MAX_DOWNLOAD) { "Import is larger than 256 MB." }
                                    destination.write(bytes, 0, read)
                                }
                            }
                        }
                        // Validate the format before showing it in the saved-log list.
                        CsvAnalysis.analyse(output, Sensor.NOX)
                        output
                    } catch (error: Exception) { output.delete(); throw error }
                }
                refreshLocalLogs()
                selectLog(LocalLog(file))
            } catch (error: Exception) { report(error) }
        }
    }
    fun exportLog(log: LocalLog, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { output -> log.file.inputStream().use { it.copyTo(output) } }
                }
                notify("CSV exported successfully.")
            } catch (error: Exception) { report(error) }
        }
    }
    fun selectLog(log: LocalLog) {
        _state.update { it.copy(selectedLog = log, page = Page.ANALYSIS) }
        analyse()
    }
    fun analyse(sensor: Sensor = _state.value.sensor, from: LocalDateTime? = null, until: LocalDateTime? = null) {
        val file = _state.value.selectedLog?.file ?: return
        analysisJob?.cancel()
        _state.update { it.copy(sensor = sensor, analysing = true, analysis = null, analysisError = null) }
        analysisJob = viewModelScope.launch {
            try {
                val analysis = runInterruptible(Dispatchers.IO) { CsvAnalysis.analyse(file, sensor, from, until) }
                _state.update { it.copy(analysis = analysis, analysing = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(analysing = false, analysisError = friendly(error)) } }
        }
    }

    fun discoverPanes() {
        if (_state.value.demo) { notify("Screen capture is available when connected to your Pi."); return }
        if (!_state.value.settings.configured) { setPage(Page.SETTINGS); return }
        if (_state.value.screenBusy) return
        _state.update { it.copy(screenBusy = true, screenStatus = "Finding existing tmux panes…") }
        viewModelScope.launch {
            try {
                val panes = withContext(Dispatchers.IO) { pi.panes(_state.value.settings) }
                val guess = if (_state.value.selectedPane == null) likelyDashboard(panes) else null
                _state.update { it.copy(panes = panes, screenBusy = false,
                    screenStatus = if (panes.isEmpty()) "No running tmux panes were found." else "Select the pane running your dashboard.") }
                guess?.let(::selectPane)
            } catch (error: Exception) {
                _state.update { it.copy(screenBusy = false, screenStatus = "Could not find the dashboard session.") }
                report(error, ::discoverPanes)
            }
        }
    }
    fun selectPane(pane: TerminalPane) {
        _state.update { it.copy(selectedPane = pane, terminal = "") }
        captureScreen()
        updateScreenPolling()
    }
    fun autoRefresh(enabled: Boolean) { _state.update { it.copy(screenAutoRefresh = enabled) }; updateScreenPolling() }
    private fun updateScreenPolling() {
        screenJob?.cancel()
        if (!foreground || _state.value.page != Page.SCREEN || !_state.value.screenAutoRefresh || _state.value.selectedPane == null || _state.value.demo) return
        screenJob = viewModelScope.launch {
            while (isActive) { delay(3000); captureScreen() }
        }
    }
    fun captureScreen() {
        val snapshot = _state.value
        val pane = snapshot.selectedPane ?: return
        if (snapshot.screenBusy) return
        _state.update { it.copy(screenBusy = true) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { pi.screen(snapshot.settings, pane) }
                _state.update { it.copy(terminal = text, terminalUpdated = System.currentTimeMillis(), screenBusy = false,
                    screenStatus = "Read-only · ${pane.label}") }
            } catch (error: Exception) {
                _state.update { it.copy(screenBusy = false, screenStatus = friendly(error), screenAutoRefresh = false) }
                updateScreenPolling()
                report(error, ::captureScreen)
            }
        }
    }

    fun startDemo() {
        stopMqtt()
        screenJob?.cancel()
        _state.update { it.copy(demo = true, page = Page.LIVE, mqttConnected = false, mqttStatus = "Sample data · no Pi connection") }
        startDemoTicks()
        viewModelScope.launch(Dispatchers.IO) {
            val directory = File(root, "sample").apply { mkdirs() }
            val file = File(directory, "airsense_log_2026-01-01.csv")
            if (!file.exists()) file.bufferedWriter().use { output ->
                output.appendLine("Date,Time,NOx (ppm),Light (lux),Noise (dB),PM2.5 (ug/m3),PM10 (ug/m3),SO2 (ppm),NH3 (ppm)")
                repeat(3600) { n ->
                    val time = java.time.LocalTime.of(9, 0).plusSeconds(n.toLong())
                    val phase = n / 140.0
                    output.appendLine(String.format(Locale.US, "2026-01-01,%s,%.3f,%.1f,%.2f,%s,%.2f,%.3f,%.2f",
                        time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), .7 + .5 * kotlin.math.sin(phase),
                        350 + 120 * kotlin.math.sin(phase / 2), 65 + 9 * kotlin.math.sin(phase),
                        if (n in 1500..1580) "Nil" else String.format(Locale.US, "%.2f", 30 + 24 * kotlin.math.sin(phase)),
                        50 + 34 * kotlin.math.sin(phase), .12 + .09 * kotlin.math.sin(phase), 8 + 5 * kotlin.math.sin(phase)))
                }
            }
            refreshLocalLogs()
        }
    }
    private fun startDemoTicks() {
        demoJob?.cancel()
        demoJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val phase = tick++ / 10.0
                val values = mapOf(Sensor.NOX to .72 + .18 * kotlin.math.sin(phase), Sensor.SO2 to .12,
                    Sensor.NH3 to 8.4, Sensor.PM25 to 28.0 + 8 * kotlin.math.sin(phase), Sensor.PM10 to 43.2,
                    Sensor.NOISE to 65.7, Sensor.LUX to 370.0)
                _state.update { it.copy(live = LiveReading(values, "SAMPLE · 2026-01-01 09:00:00", android.os.SystemClock.elapsedRealtime()),
                    mqttStatus = "Sample data · no Pi connection") }
                delay(1000)
            }
        }
    }
    fun exitDemo() {
        demoJob?.cancel()
        _state.update { it.copy(demo = false, live = LiveReading(), page = Page.SETTINGS, mqttStatus = "Disconnected") }
    }
    fun forgetCredentials() {
        disconnect()
        demoJob?.cancel()
        store.clearCredentials()
        _state.update { AppState(localLogs = it.localLogs, notice = "Credentials and trusted fingerprints removed from this phone.") }
    }
    override fun onCleared() { subscriber?.close(); super.onCleared() }
}
